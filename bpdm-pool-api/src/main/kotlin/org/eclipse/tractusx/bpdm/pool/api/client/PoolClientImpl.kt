/*******************************************************************************
 * Copyright (c) 2021,2024 Contributors to the Eclipse Foundation
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

package org.eclipse.tractusx.bpdm.pool.api.client

import org.eclipse.tractusx.bpdm.common.service.ParameterObjectArgumentResolver
import org.eclipse.tractusx.bpdm.pool.api.*
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.support.WebClientAdapter
import org.springframework.web.service.invoker.HttpServiceProxyFactory
import java.time.Duration

/**
 * In a Spring configuration a bean of this class is instantiated passing a webClientProvider which configures the web client with e.g. OIDC configuration.
 * A lazy HttpServiceProxyFactory private property is defined: On first access it creates a HttpServiceProxyFactory configured with the web client.
 * Several lazy API clients are defined: On first access they are created from the HttpServiceProxyFactory for the specific API interface.
 * All this has to be done lazily because during integration tests the web client URL may not be available yet on Spring initialization.
 */
class PoolClientImpl(
    private val webClientProvider: () -> WebClient
) : PoolApiClient {

    private val httpServiceProxyFactory: HttpServiceProxyFactory by lazy {
        HttpServiceProxyFactory
            .builder(WebClientAdapter.forClient(webClientProvider()))
            .customArgumentResolver(ParameterObjectArgumentResolver())
            .blockTimeout(Duration.ofSeconds(30))
            .build()
    }

    override val addresses by lazy { createClient<PoolAddressApi>() }

    override val bpns by lazy { createClient<PoolBpnApi>() }

    override val changelogs by lazy { createClient<PoolChangelogApi>() }

    override val legalEntities by lazy { createClient<PoolLegalEntityApi>() }

    override val metadata by lazy { createClient<PoolMetadataApi>() }

    override val sites by lazy { createClient<PoolSiteApi>() }

    override val saas by lazy { createClient<PoolSaasApi>() }

    private inline fun <reified T> createClient() =
        httpServiceProxyFactory.createClient(T::class.java)
}
