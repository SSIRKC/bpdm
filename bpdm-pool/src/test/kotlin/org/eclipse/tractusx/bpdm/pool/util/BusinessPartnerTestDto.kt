/*******************************************************************************
 * Copyright (c) 2021,2022 Contributors to the Eclipse Foundation
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

package org.eclipse.tractusx.bpdm.pool.util

import org.eclipse.tractusx.bpdm.pool.dto.request.AddressRequest
import org.eclipse.tractusx.bpdm.pool.dto.request.LegalEntityCreateRequest
import org.eclipse.tractusx.bpdm.pool.dto.request.SiteCreateRequest
import org.eclipse.tractusx.bpdm.pool.dto.response.AddressCreateResponse
import org.eclipse.tractusx.bpdm.pool.dto.response.LegalEntityPoolUpsertResponse
import org.eclipse.tractusx.bpdm.pool.dto.response.SiteUpsertResponse

data class LegalEntityStructureRequest(
    val legalEntity: LegalEntityCreateRequest,
    val siteStructures: List<SiteStructureRequest> = emptyList(),
    val addresses: List<AddressRequest> = emptyList()
)

data class SiteStructureRequest(
    val site: SiteCreateRequest,
    val addresses: List<AddressRequest> = emptyList()
)

data class LegalEntityStructureResponse(
    val legalEntity: LegalEntityPoolUpsertResponse,
    val siteStructures: List<SiteStructureResponse> = emptyList(),
    val addresses: List<AddressCreateResponse> = emptyList()
)

data class SiteStructureResponse(
    val site: SiteUpsertResponse,
    val addresses: List<AddressCreateResponse> = emptyList()
)