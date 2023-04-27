/*
 * Tencent is pleased to support the open source community by making Spring Cloud Tencent available.
 *
 * Copyright (C) 2019 THL A29 Limited, a Tencent company. All rights reserved.
 *
 * Licensed under the BSD 3-Clause License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.tencent.cloud.rpc.enhancement.scg;

import java.net.URI;

import com.tencent.cloud.rpc.enhancement.plugin.EnhancedPluginContext;
import com.tencent.cloud.rpc.enhancement.plugin.EnhancedPluginRunner;
import com.tencent.cloud.rpc.enhancement.plugin.EnhancedRequestContext;
import com.tencent.cloud.rpc.enhancement.plugin.EnhancedResponseContext;
import reactor.core.publisher.Mono;

import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.ReactiveLoadBalancerClientFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;

import static com.tencent.cloud.rpc.enhancement.plugin.EnhancedPluginType.EXCEPTION;
import static com.tencent.cloud.rpc.enhancement.plugin.EnhancedPluginType.FINALLY;
import static com.tencent.cloud.rpc.enhancement.plugin.EnhancedPluginType.POST;
import static com.tencent.cloud.rpc.enhancement.plugin.EnhancedPluginType.PRE;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

/**
 * EnhancedGatewayGlobalFilter.
 *
 * @author sean yu
 */
public class EnhancedGatewayGlobalFilter implements GlobalFilter, Ordered {

	private final EnhancedPluginRunner pluginRunner;

	public EnhancedGatewayGlobalFilter(EnhancedPluginRunner pluginRunner) {
		this.pluginRunner = pluginRunner;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		EnhancedPluginContext enhancedPluginContext = new EnhancedPluginContext();

		EnhancedRequestContext enhancedRequestContext = EnhancedRequestContext.builder()
				.httpHeaders(exchange.getRequest().getHeaders())
				.httpMethod(exchange.getRequest().getMethod())
				.url(exchange.getRequest().getURI())
				.build();
		enhancedPluginContext.setRequest(enhancedRequestContext);

		// Run pre enhanced plugins.
		pluginRunner.run(PRE, enhancedPluginContext);

		long startTime = System.currentTimeMillis();
		return chain.filter(exchange)
				.doOnSubscribe(v -> {
					DefaultServiceInstance serviceInstance = new DefaultServiceInstance();
					Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
					if (route != null) {
						serviceInstance.setServiceId(route.getUri().getHost());
					}
					URI uri = exchange.getAttribute(GATEWAY_REQUEST_URL_ATTR);
					if (uri != null) {
						serviceInstance.setHost(uri.getHost());
						serviceInstance.setPort(uri.getPort());
					}
					enhancedPluginContext.setServiceInstance(serviceInstance);
				})
				.doOnSuccess(v -> {
					enhancedPluginContext.setDelay(System.currentTimeMillis() - startTime);
					EnhancedResponseContext enhancedResponseContext = EnhancedResponseContext.builder()
							.httpStatus(exchange.getResponse().getRawStatusCode())
							.httpHeaders(exchange.getResponse().getHeaders())
							.build();
					enhancedPluginContext.setResponse(enhancedResponseContext);

					// Run post enhanced plugins.
					pluginRunner.run(POST, enhancedPluginContext);
				})
				.doOnError(t -> {
					enhancedPluginContext.setDelay(System.currentTimeMillis() - startTime);
					enhancedPluginContext.setThrowable(t);

					// Run exception enhanced plugins.
					pluginRunner.run(EXCEPTION, enhancedPluginContext);
				})
				.doFinally(v -> {
					// Run finally enhanced plugins.
					pluginRunner.run(FINALLY, enhancedPluginContext);
				});
	}

	/**
	 * {@link ReactiveLoadBalancerClientFilter}.LOAD_BALANCER_CLIENT_FILTER_ORDER = 10150.
	 */
	@Override
	public int getOrder() {
		return 10150 + 1;
	}
}