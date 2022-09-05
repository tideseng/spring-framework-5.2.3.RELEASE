/*
 * Copyright 2002-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.web.servlet.config.annotation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.core.OrderComparator;
import org.springframework.core.Ordered;
import org.springframework.web.context.request.WebRequestInterceptor;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.handler.WebRequestHandlerInterceptorAdapter;

/**
 * Helps with configuring a list of mapped interceptors.
 *
 * @author Rossen Stoyanchev
 * @author Keith Donald
 * @since 3.1
 */
public class InterceptorRegistry { // 拦截器注册器

	private final List<InterceptorRegistration> registrations = new ArrayList<>(); // 拦截器列表


	/**
	 * Adds the provided {@link HandlerInterceptor}.
	 * @param interceptor the interceptor to add
	 * @return an {@link InterceptorRegistration} that allows you optionally configure the
	 * registered interceptor further for example adding URL patterns it should apply to.
	 */
	public InterceptorRegistration addInterceptor(HandlerInterceptor interceptor) { // 添加拦截器
		InterceptorRegistration registration = new InterceptorRegistration(interceptor); // 创建InterceptorRegistration
		this.registrations.add(registration); // 添加拦截器
		return registration;
	}

	/**
	 * Adds the provided {@link WebRequestInterceptor}.
	 * @param interceptor the interceptor to add
	 * @return an {@link InterceptorRegistration} that allows you optionally configure the
	 * registered interceptor further for example adding URL patterns it should apply to.
	 */
	public InterceptorRegistration addWebRequestInterceptor(WebRequestInterceptor interceptor) {
		WebRequestHandlerInterceptorAdapter adapted = new WebRequestHandlerInterceptorAdapter(interceptor);
		InterceptorRegistration registration = new InterceptorRegistration(adapted);
		this.registrations.add(registration);
		return registration;
	}

	/**
	 * Return all registered interceptors.
	 */
	protected List<Object> getInterceptors() { // 获取拦截器列表（返回的可能是真实的拦截器，也可能是包装类MappedInterceptor）
		return this.registrations.stream()
				.sorted(INTERCEPTOR_ORDER_COMPARATOR) // 排序
				.map(InterceptorRegistration::getInterceptor) // 获取当前拦截器（返回的可能是真实的拦截器，也可能是包装类MappedInterceptor）
				.collect(Collectors.toList());
	}


	private static final Comparator<Object> INTERCEPTOR_ORDER_COMPARATOR =
			OrderComparator.INSTANCE.withSourceProvider(object -> {
				if (object instanceof InterceptorRegistration) {
					return (Ordered) ((InterceptorRegistration) object)::getOrder;
				}
				return null;
			});

}
