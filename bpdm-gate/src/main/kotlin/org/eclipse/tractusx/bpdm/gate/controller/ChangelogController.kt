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

package org.eclipse.tractusx.bpdm.gate.controller

import org.eclipse.tractusx.bpdm.common.dto.request.PaginationRequest
import org.eclipse.tractusx.bpdm.common.dto.response.PageResponse
import org.eclipse.tractusx.bpdm.gate.api.GateChangelogApi
import org.eclipse.tractusx.bpdm.gate.api.model.response.ChangelogResponse
import org.eclipse.tractusx.bpdm.gate.api.model.response.LsaType
import org.eclipse.tractusx.bpdm.gate.api.model.response.PageChangeLogResponse
import org.eclipse.tractusx.bpdm.gate.service.ChangelogService
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@Validated
class ChangelogController(
    private val changelogService: ChangelogService
) : GateChangelogApi {

    override fun getChangelogEntriesExternalId(
        paginationRequest: PaginationRequest, fromTime: Instant?, externalIds: Set<String>
    ): PageChangeLogResponse<ChangelogResponse> {
        return changelogService.getChangeLogByExternalId(externalIds, fromTime, paginationRequest.page, paginationRequest.size)
    }

    override fun getChangelogEntriesLsaType(
        paginationRequest: PaginationRequest, fromTime: Instant?, lsaType: LsaType?
    ): PageResponse<ChangelogResponse> {
        return changelogService.getChangeLogByLsaType(lsaType, fromTime, paginationRequest.page, paginationRequest.size)
    }


}