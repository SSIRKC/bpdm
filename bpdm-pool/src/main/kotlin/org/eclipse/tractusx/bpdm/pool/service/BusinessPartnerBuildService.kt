/*******************************************************************************
 * Copyright (c) 2021,2023 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Apache License, Version 2.0 which is available at
 * https://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 ******************************************************************************/

package org.eclipse.tractusx.bpdm.pool.service

import com.neovisionaries.i18n.LanguageCode
import mu.KotlinLogging
import org.eclipse.tractusx.bpdm.common.dto.*
import org.eclipse.tractusx.bpdm.common.exception.BpdmNotFoundException
import org.eclipse.tractusx.bpdm.common.model.NameType
import org.eclipse.tractusx.bpdm.pool.api.model.ChangelogType
import org.eclipse.tractusx.bpdm.pool.api.model.request.*
import org.eclipse.tractusx.bpdm.pool.api.model.response.*
import org.eclipse.tractusx.bpdm.pool.dto.ChangelogEntryDto
import org.eclipse.tractusx.bpdm.pool.dto.MetadataMappingDto
import org.eclipse.tractusx.bpdm.pool.entity.*
import org.eclipse.tractusx.bpdm.pool.repository.AddressPartnerRepository
import org.eclipse.tractusx.bpdm.pool.repository.IdentifierRepository
import org.eclipse.tractusx.bpdm.pool.repository.LegalEntityRepository
import org.eclipse.tractusx.bpdm.pool.repository.SiteRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Service for creating and updating business partner records
 */
@Service
class BusinessPartnerBuildService(
    private val bpnIssuingService: BpnIssuingService,
    private val legalEntityRepository: LegalEntityRepository,
    private val businessPartnerFetchService: BusinessPartnerFetchService,
    private val metadataMappingService: MetadataMappingService,
    private val changelogService: PartnerChangelogService,
    private val siteRepository: SiteRepository,
    private val addressPartnerRepository: AddressPartnerRepository,
    private val identifierRepository: IdentifierRepository
) {

    private val logger = KotlinLogging.logger { }

    /**
     * Create new business partner records from [requests]
     */
    @Transactional
    fun createLegalEntities(requests: Collection<LegalEntityPartnerCreateRequest>): LegalEntityPartnerCreateResponseWrapper {
        logger.info { "Create ${requests.size} new legal entities" }

        val errors = mutableListOf<ErrorInfo<LegalEntityCreateError>>()
        val validRequests = filterLegalEntityDuplicatesByIdentifier(requests, errors)

        val metadataMap = metadataMappingService.mapRequests(validRequests.map { it.properties })

        val bpnLs = bpnIssuingService.issueLegalEntityBpns(validRequests.size)
        val requestWithBpnPairs = validRequests.zip(bpnLs)

        val legalEntityWithIndexByBpnMap = requestWithBpnPairs
            .map { (request, bpnL) -> Pair(createLegalEntity(request.properties, bpnL, metadataMap), request.index) }
            .associateBy { (legalEntity, _) -> legalEntity.bpn }
        val legalEntities = legalEntityWithIndexByBpnMap.values.map { (legalEntity, _) -> legalEntity }

        changelogService.createChangelogEntries(legalEntities.map { ChangelogEntryDto(it.bpn, ChangelogType.CREATE, ChangelogSubject.LEGAL_ENTITY) })
        legalEntityRepository.saveAll(legalEntities)

        val validEntities = legalEntities.map { it.toUpsertDto(legalEntityWithIndexByBpnMap[it.bpn]!!.second) }

        return EntitiesWithErrors(validEntities, errors)
    }

    @Transactional
    fun createSites(requests: Collection<SitePartnerCreateRequest>): SitePartnerCreateResponseWrapper {
        logger.info { "Create ${requests.size} new sites" }

        val legalEntities = legalEntityRepository.findDistinctByBpnIn(requests.map { it.legalEntity })
        val legalEntityMap = legalEntities.associateBy { it.bpn }

        val (validRequests, invalidRequests) = requests.partition { legalEntityMap[it.legalEntity] != null }
        val errors = invalidRequests.map {
            ErrorInfo(SiteCreateError.LegalEntityNotFound, "Site not created: parent legal entity ${it.legalEntity} not found", it.index)
        }

        val bpnSs = bpnIssuingService.issueSiteBpns(validRequests.size)
        val requestBpnPairs = validRequests.zip(bpnSs)
        val bpnsMap = requestBpnPairs
            .map { (request, bpns) -> Pair(createSite(request.site, bpns, legalEntityMap[request.legalEntity]!!), request.index) }
            .associateBy { (site, _) -> site.bpn }
        val sites = bpnsMap.values.map { (site, _) -> site }

        changelogService.createChangelogEntries(sites.map { ChangelogEntryDto(it.bpn, ChangelogType.CREATE, ChangelogSubject.SITE) })
        siteRepository.saveAll(sites)

        val validEntities = sites.map { it.toUpsertDto(bpnsMap[it.bpn]!!.second) }

        return EntitiesWithErrors(validEntities, errors)
    }

    @Transactional
    fun createAddresses(requests: Collection<AddressPartnerCreateRequest>): AddressPartnerCreateResponseWrapper {
        logger.info { "Create ${requests.size} new addresses" }
        fun isLegalEntityRequest(request: AddressPartnerCreateRequest) = request.parent.startsWith(bpnIssuingService.bpnlPrefix)
        fun isSiteRequest(request: AddressPartnerCreateRequest) = request.parent.startsWith(bpnIssuingService.bpnsPrefix)

        val (legalEntityRequests, otherAddresses) = requests.partition { isLegalEntityRequest(it) }
        val (siteRequests, invalidAddresses) = otherAddresses.partition { isSiteRequest(it) }

        val errors = mutableListOf<ErrorInfo<AddressCreateError>>()
        invalidAddresses.map {
            ErrorInfo(AddressCreateError.BpnNotValid, "Address not created: parent ${it.parent} is not a valid BPNL/BPNS", it.index)
        }.forEach(errors::add)
        val addressResponses = createSiteAddressResponses(siteRequests, errors).toMutableList()
        addressResponses.addAll(createLegalEntityAddressResponses(legalEntityRequests, errors))

        changelogService.createChangelogEntries(addressResponses.map { ChangelogEntryDto(it.bpn, ChangelogType.CREATE, ChangelogSubject.ADDRESS) })

        return EntitiesWithErrors(addressResponses, errors)
    }

    /**
     * Update existing records with [requests]
     */
    @Transactional
    fun updateLegalEntities(requests: Collection<LegalEntityPartnerUpdateRequest>): LegalEntityPartnerUpdateResponseWrapper {
        logger.info { "Update ${requests.size} legal entities" }
        val metadataMap = metadataMappingService.mapRequests(requests.map { it.properties })

        val bpnsToFetch = requests.map { it.bpn }
        val legalEntities = legalEntityRepository.findDistinctByBpnIn(bpnsToFetch)
        businessPartnerFetchService.fetchDependenciesWithLegalAddress(legalEntities)

        val bpnsNotFetched = bpnsToFetch.minus(legalEntities.map { it.bpn }.toSet())
        val errors = bpnsNotFetched.map {
            ErrorInfo(LegalEntityUpdateError.LegalEntityNotFound, "Legal entity $it not updated: BPNL not found", it)
        }

        val requestByBpnMap = requests.associateBy { it.bpn }
        legalEntities.forEach { updateLegalEntity(it, requestByBpnMap.get(it.bpn)!!.properties, metadataMap) }

        changelogService.createChangelogEntries(legalEntities.map { ChangelogEntryDto(it.bpn, ChangelogType.UPDATE, ChangelogSubject.LEGAL_ENTITY) })

        val validEntities = legalEntityRepository.saveAll(legalEntities).map { it.toUpsertDto(null) }

        return EntitiesWithErrors(validEntities, errors)
    }

    @Transactional
    fun updateSites(requests: Collection<SitePartnerUpdateRequest>): SitePartnerUpdateResponseWrapper {
        logger.info { "Update ${requests.size} sites" }

        val bpnsToFetch = requests.map { it.bpn }
        val sites = siteRepository.findDistinctByBpnIn(bpnsToFetch)

        val bpnsNotFetched = bpnsToFetch.minus(sites.map { it.bpn }.toSet())
        val errors = bpnsNotFetched.map {
            ErrorInfo(SiteUpdateError.SiteNotFound, "Site $it not updated: BPNS not found", it)
        }

        changelogService.createChangelogEntries(sites.map { ChangelogEntryDto(it.bpn, ChangelogType.UPDATE, ChangelogSubject.SITE) })

        val requestByBpnMap = requests.associateBy { it.bpn }
        sites.forEach { updateSite(it, requestByBpnMap[it.bpn]!!.site) }
        val validEntities = siteRepository.saveAll(sites).map { it.toUpsertDto(null) }

        return EntitiesWithErrors(validEntities, errors)
    }

    fun updateAddresses(requests: Collection<AddressPartnerUpdateRequest>): AddressPartnerUpdateResponseWrapper {
        logger.info { "Update ${requests.size} business partner addresses" }

        val validAddresses = addressPartnerRepository.findDistinctByBpnIn(requests.map { it.bpn })
        val validBpns = validAddresses.map { it.bpn }.toHashSet()
        val errors = requests.filter { !validBpns.contains(it.bpn) }.map {
            ErrorInfo(AddressUpdateError.AddressNotFound, "Address ${it.bpn} not updated: BPNA not found", it.bpn)
        }

        val requestMap = requests.associateBy { it.bpn }
        validAddresses.forEach { updateAddress(it.address, requestMap[it.bpn]!!.properties) }

        changelogService.createChangelogEntries(validAddresses.map { ChangelogEntryDto(it.bpn, ChangelogType.UPDATE, ChangelogSubject.ADDRESS) })

        val addressResponses =  addressPartnerRepository.saveAll(validAddresses).map { it.toPoolDto() }
        return EntitiesWithErrors(addressResponses, errors)
    }

    @Transactional
    fun setBusinessPartnerCurrentness(bpn: String) {
        logger.info { "Updating currentness of business partner $bpn" }
        val partner = legalEntityRepository.findByBpn(bpn) ?: throw BpdmNotFoundException("Business Partner", bpn)
        partner.currentness = createCurrentnessTimestamp()
        legalEntityRepository.save(partner)
    }

    private fun createLegalEntityAddressResponses(requests: Collection<AddressPartnerCreateRequest>,
                                                  errors: MutableList<ErrorInfo<AddressCreateError>>): Collection<AddressPartnerCreateResponse> {

        fun findValidLegalEnities(requests: Collection<AddressPartnerCreateRequest>): Map<String, LegalEntity> {
            val bpnLsToFetch = requests.map { it.parent }
            val legalEntities = businessPartnerFetchService.fetchByBpns(bpnLsToFetch)
            val bpnl2LegalEntityMap = legalEntities.associateBy { it.bpn }
            return bpnl2LegalEntityMap
        }

        val bpnl2LegalEntityMap = findValidLegalEnities(requests)
        val (validRequests, invalidRequests) = requests.partition { bpnl2LegalEntityMap[it.parent] != null }

        errors.addAll(invalidRequests.map {
            ErrorInfo(AddressCreateError.LegalEntityNotFound, "Address not created: parent legal entity ${it.parent} not found", it.index)
        })

        val bpnAs = bpnIssuingService.issueAddressBpns(validRequests.size)
        val validAddressesByIndex = validRequests
            .zip(bpnAs)
            .map { (request, bpna) -> Pair(request.index, createPartnerAddress(request.properties, bpna, bpnl2LegalEntityMap[request.parent], null)) }
        addressPartnerRepository.saveAll(validAddressesByIndex.map{it.second})
        return validAddressesByIndex.map { it.second.toCreateResponse(it.first) }
    }

    private fun createSiteAddressResponses(requests: Collection<AddressPartnerCreateRequest>,
                                           errors: MutableList<ErrorInfo<AddressCreateError>>): List<AddressPartnerCreateResponse> {

        fun findValidSites(requests: Collection<AddressPartnerCreateRequest>): Map<String, Site> {
            val bpnsToFetch = requests.map { it.parent }
            val sites = siteRepository.findDistinctByBpnIn(bpnsToFetch)
            val bpns2SiteMap = sites.associateBy { it.bpn }
            return bpns2SiteMap
        }

        val bpns2SiteMap = findValidSites(requests)
        val (validRequests, invalidRequests) = requests.partition { bpns2SiteMap[it.parent] != null }
        errors.addAll(invalidRequests.map {
            ErrorInfo(AddressCreateError.SiteNotFound, "Address not created: site ${it.parent} not found", it.index)
        })

        val bpnAs = bpnIssuingService.issueAddressBpns(validRequests.size)
        val validAddressesByIndex = validRequests
            .zip(bpnAs)
            .map { (request, bpna) -> Pair(request.index, createPartnerAddress(request.properties, bpna, null, bpns2SiteMap[request.parent])) }

        addressPartnerRepository.saveAll(validAddressesByIndex.map{it.second})
        return validAddressesByIndex.map { it.second.toCreateResponse(it.first) }
    }

    private fun createLegalEntity(
        dto: LegalEntityDto,
        bpnL: String,
        metadataMap: MetadataMappingDto
    ): LegalEntity {
        val legalForm = if (dto.legalForm != null) metadataMap.legalForms[dto.legalForm]!! else null

        val legalAddress = createAddress(dto.legalAddress)
        val partner = LegalEntity(
            bpn = bpnL,
            legalForm = legalForm,
            types = mutableSetOf(),
            roles = mutableSetOf(),
            currentness = Instant.now().truncatedTo(ChronoUnit.MICROS),
            legalAddress = legalAddress
        )

        return updateLegalEntity(partner, dto, metadataMap)
    }

    private fun createSite(
        dto: SiteDto,
        bpnS: String,
        partner: LegalEntity
    ): Site {
        val mainAddress = createAddress(dto.mainAddress)
        val site = Site(
            bpn = bpnS,
            name = dto.name,
            legalEntity = partner,
            mainAddress = mainAddress
        )

        return site
    }


    private fun updateLegalEntity(
        partner: LegalEntity,
        request: LegalEntityDto,
        metadataMap: MetadataMappingDto
    ): LegalEntity {

        partner.currentness = createCurrentnessTimestamp()

        partner.names.clear()
        partner.identifiers.clear()
        partner.stati.clear()
        partner.classification.clear()
        partner.bankAccounts.clear()

        partner.legalForm = if (request.legalForm != null) metadataMap.legalForms[request.legalForm]!! else null
        partner.stati.addAll(request.status.map { toEntity(it, partner) })
        partner.names.addAll(request.legalName.let { listOf(toEntity(it, partner)) })
        partner.identifiers.addAll(request.identifiers.map { toEntity(it, metadataMap, partner) })
        partner.classification.addAll(request.classifications.map { toEntity(it, partner) }.toSet())

        updateAddress(partner.legalAddress, request.legalAddress)

        return partner
    }

    private fun updateSite(site: Site, request: SiteDto): Site {
        site.name = request.name

        updateAddress(site.mainAddress, request.mainAddress)

        return site
    }

    private fun createAddress(
        dto: AddressDto
    ): Address {
        val address = Address(
            careOf = dto.careOf,
            contexts = dto.contexts.toMutableSet(),
            country = dto.country,
            types = dto.types.toMutableSet(),
            version = toEntity(dto.version),
            geoCoordinates = dto.geographicCoordinates?.let { toEntity(dto.geographicCoordinates!!) }
        )

        return updateAddress(address, dto)
    }

    private fun createPartnerAddress(
        dto: AddressDto,
        bpn: String,
        partner: LegalEntity?,
        site: Site?
    ): AddressPartner {
        val addressPartner = AddressPartner(
            bpn,
            partner,
            site,
            createAddress(dto)
        )

        updateAddress(addressPartner.address, dto)

        return addressPartner
    }

    private fun updateAddress(address: Address, dto: AddressDto): Address{
        address.careOf = dto.careOf
        address.country = dto.country
        address.geoCoordinates =  dto.geographicCoordinates?.let { toEntity(dto.geographicCoordinates!!) }

        address.administrativeAreas.clear()
        address.postCodes.clear()
        address.thoroughfares.clear()
        address.localities.clear()
        address.premises.clear()
        address.postalDeliveryPoints.clear()
        address.contexts.clear()
        address.types.clear()

        address.administrativeAreas.addAll(dto.administrativeAreas.map { toEntity(it, address) }.toSet())
        address.postCodes.addAll(dto.postCodes.map { toEntity(it, address) }.toSet())
        address.thoroughfares.addAll(dto.thoroughfares.map { toEntity(it, address) }.toSet())
        address.localities.addAll(dto.localities.map { toEntity(it, address) }.toSet())
        address.premises.addAll(dto.premises.map { toEntity(it, address) }.toSet())
        address.postalDeliveryPoints.addAll(dto.postalDeliveryPoints.map { toEntity(it, address) }.toSet())
        address.contexts.addAll(dto.contexts)
        address.types.addAll(dto.types)

        return address
    }

    private fun toEntity(dto: LegalEntityStatusDto, partner: LegalEntity): BusinessStatus {
        return BusinessStatus(
            description = dto.officialDenotation,
            validFrom = dto.validFrom,
            validUntil = dto.validUntil,
            type = dto.type,
            legalEntity = partner
        )
    }

    private fun toEntity(dto: SiteStatusDto, partner: LegalEntity): BusinessStatus {
        return BusinessStatus(
            description = dto.description,
            validFrom = dto.validFrom,
            validUntil = dto.validUntil,
            type = dto.type,
            legalEntity = partner
        )
    }

    private fun toEntity(dto: BankAccountDto, partner: LegalEntity): BankAccount {
        return BankAccount(
            dto.trustScores.toMutableSet(),
            dto.currency,
            dto.internationalBankAccountIdentifier,
            dto.internationalBankIdentifier,
            dto.nationalBankAccountIdentifier,
            dto.nationalBankIdentifier,
            partner
        )
    }

    private fun toEntity(dto: NameDto, partner: LegalEntity): Name {
        // TODO
        return Name(
            value = dto.value,
            shortName = dto.shortName,
            type = NameType.OTHER,
            language = LanguageCode.undefined,
            legalEntity = partner
        )
    }

    private fun toEntity(dto: ClassificationDto, partner: LegalEntity): Classification {
        return Classification(
            value = dto.value,
            code = dto.code,
            type = dto.type,
            legalEntity = partner
        )
    }

    private fun toEntity(
        dto: IdentifierDto,
        metadataMap: MetadataMappingDto,
        partner: LegalEntity
    ): Identifier {
        return Identifier(
            value = dto.value,
            type = metadataMap.idTypes[dto.type]!!,
            issuingBody = dto.issuingBody,
            legalEntity = partner
        )
    }

    private fun toEntity(dto: AddressVersionDto): AddressVersion {
        return AddressVersion(dto.characterSet, dto.language)
    }

    private fun toEntity(dto: GeoCoordinateDto): GeographicCoordinate {
        return GeographicCoordinate(dto.latitude, dto.longitude, dto.altitude)
    }

    private fun toEntity(dto: ThoroughfareDto, address: Address): Thoroughfare {
        return Thoroughfare(dto.value, dto.name, dto.shortName, dto.number, dto.direction, dto.type, address.version.language, address)
    }

    private fun toEntity(dto: LocalityDto, address: Address): Locality {
        return Locality(dto.value, dto.shortName, dto.type, address.version.language, address)
    }

    private fun toEntity(dto: PremiseDto, address: Address): Premise {
        return Premise(dto.value, dto.shortName, dto.number, dto.type, address.version.language, address)
    }

    private fun toEntity(dto: PostalDeliveryPointDto, address: Address): PostalDeliveryPoint {
        return PostalDeliveryPoint(dto.value, dto.shortName, dto.number, dto.type, address.version.language, address)
    }

    private fun toEntity(dto: AdministrativeAreaDto, address: Address): AdministrativeArea {
        return AdministrativeArea(dto.value, dto.shortName, dto.fipsCode, dto.type, address.version.language, address.country, address)
    }

    private fun toEntity(dto: PostCodeDto, address: Address): PostCode {
        return PostCode(dto.value, dto.type, address.country, address)
    }

    private fun filterLegalEntityDuplicatesByIdentifier(
        requests: Collection<LegalEntityPartnerCreateRequest>, errors: MutableList<ErrorInfo<LegalEntityCreateError>>): Collection<LegalEntityPartnerCreateRequest> {

        val idValues = requests.flatMap { it.properties.identifiers }.map { it.value }
        val idsInDb = identifierRepository.findByValueIn(idValues).map { Pair(it.value, it.type.technicalKey) }.toHashSet()

        val (invalidRequests, validRequests) = requests.partition {
            it.properties.identifiers.map { id -> Pair(id.value, id.type) }.any { id -> idsInDb.contains(id) }
        }

        invalidRequests.map { 
            ErrorInfo(LegalEntityCreateError.LegalEntityDuplicateIdentifier, "Legal entity not created: duplicate identifier", it.index)
        }.forEach(errors::add)

        return validRequests
    }

    private fun createCurrentnessTimestamp(): Instant{
        return Instant.now().truncatedTo(ChronoUnit.MICROS)
    }
}