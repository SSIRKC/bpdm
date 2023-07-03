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

package org.eclipse.tractusx.bpdm.gate.api.model.response

import com.fasterxml.jackson.annotation.JsonUnwrapped
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import io.swagger.v3.oas.annotations.media.Schema
import org.eclipse.tractusx.bpdm.common.dto.LegalEntityDto
import org.eclipse.tractusx.bpdm.common.service.DataClassUnwrappedJsonDeserializer
import org.eclipse.tractusx.bpdm.gate.api.model.BusinessPartnerRole

@JsonDeserialize(using = DataClassUnwrappedJsonDeserializer::class)
@Schema(name = "LegalEntityGateOutputResponse", description = "Legal entity with external id")
data class LegalEntityGateOutputResponse(
    @field:JsonUnwrapped
    val legalEntity: LegalEntityDto,

    val legalNameParts: Collection<String>,

    @Schema(description = "Which roles this business partner takes in relation to the sharing member")
    val roles: Collection<BusinessPartnerRole> = emptyList(),

    @get:Schema(description = "Address of the official seat of this legal entity")
    val legalAddress: AddressGateOutputResponse,

    @Schema(description = "ID the record has in the external system where the record originates from", required = true)
    val externalId: String,

    @Schema(description = "Business Partner Number")
    val bpn: String

)