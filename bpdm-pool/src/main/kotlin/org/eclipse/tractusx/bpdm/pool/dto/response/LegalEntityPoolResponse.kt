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

package org.eclipse.tractusx.bpdm.pool.dto.response

import com.fasterxml.jackson.annotation.JsonUnwrapped
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import io.swagger.v3.oas.annotations.media.Schema
import org.eclipse.tractusx.bpdm.common.dto.response.LegalEntityResponse
import java.time.Instant

@JsonDeserialize(using = LegalEntityPoolResponseDeserializer::class)
@Schema(name = "Legal Entity Pool Response", description = "Legal entity business partner record")
data class LegalEntityPoolResponse(
    @Schema(description = "Business Partner Number of this legal entity")
    val bpn: String,
    @JsonUnwrapped
    val properties: LegalEntityResponse,
    @Schema(description = "The timestamp the business partner data was last indicated to be still current")
    val currentness: Instant
)

private class LegalEntityPoolResponseDeserializer(vc: Class<LegalEntityPoolResponse>?) : StdDeserializer<LegalEntityPoolResponse>(vc) {
    override fun deserialize(parser: JsonParser, ctxt: DeserializationContext): LegalEntityPoolResponse {
        val node = parser.codec.readTree<JsonNode>(parser)
        return LegalEntityPoolResponse(
            node.get(LegalEntityPoolResponse::bpn.name).textValue(),
            ctxt.readTreeAsValue(node, LegalEntityResponse::class.java),
            ctxt.readTreeAsValue(node.get(LegalEntityPoolResponse::currentness.name), Instant::class.java)
        )
    }
}
