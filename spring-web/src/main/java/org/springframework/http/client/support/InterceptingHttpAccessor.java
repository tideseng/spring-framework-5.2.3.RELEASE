/*
 * Copyright 2002-2019 the original author or authors.
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

package org.springframework.http.client.support;

import java.util.ArrayList;
import java.util.List;

import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.InterceptingClientHttpRequestFactory;
import org.springframework.lang.Nullable;
import org.springframework.util.CollectionUtils;

/**
 * Base class for {@link org.springframework.web.client.RestTemplate}
 * and other HTTP accessing gateway helpers, adding interceptor-related
 * properties to {@link HttpAccessor}'s common properties.
 *
 * <p>Not intended to be used directly.
 * See {@link org.springframework.web.client.RestTemplate} for an entry point.
 *
 * @author Arjen Poutsma
 * @author Juergen Hoeller
 * @since 3.0
 * @see ClientHttpRequestInterceptor
 * @see InterceptingClientHttpRequestFactory
 * @see org.springframework.web.client.RestTemplate
 */
public abstract class InterceptingHttpAccessor extends HttpAccessor { // Http访问拦截器（在Http访问器的基础上增加了拦截器的功能）

	private final List<ClientHttpRequestInterceptor> interceptors = new ArrayList<>(); // 请求拦截器

	@Nullable
	private volatile ClientHttpRequestFactory interceptingRequestFactory; // 请求工厂类，默认为null，当进行获取时如果有拦截器则赋值为InterceptingClientHttpRequestFactory


	/**
	 * Set the request interceptors that this accessor should use.
	 * <p>The interceptors will get immediately sorted according to their
	 * {@linkplain AnnotationAwareOrderComparator#sort(List) order}.
	 * @see #getRequestFactory()
	 * @see AnnotationAwareOrderComparator
	 */
	public void setInterceptors(List<ClientHttpRequestInterceptor> interceptors) { // 设置请求拦截器（在LoadBalancerAutoConfiguration的RestTemplateCustomizer中会进行设置）
		// Take getInterceptors() List as-is when passed in here
		if (this.interceptors != interceptors) {
			this.interceptors.clear();
			this.interceptors.addAll(interceptors);
			AnnotationAwareOrderComparator.sort(this.interceptors); // 进行排序
		}
	}

	/**
	 * Get the request interceptors that this accessor uses.
	 * <p>The returned {@link List} is active and may be modified. Note,
	 * however, that the interceptors will not be resorted according to their
	 * {@linkplain AnnotationAwareOrderComparator#sort(List) order} before the
	 * {@link ClientHttpRequestFactory} is built.
	 */
	public List<ClientHttpRequestInterceptor> getInterceptors() { // 获取请求拦截器
		return this.interceptors;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void setRequestFactory(ClientHttpRequestFactory requestFactory) {
		super.setRequestFactory(requestFactory);
		this.interceptingRequestFactory = null;
	}

	/**
	 * Overridden to expose an {@link InterceptingClientHttpRequestFactory}
	 * if necessary.
	 * @see #getInterceptors()
	 */
	@Override
	public ClientHttpRequestFactory getRequestFactory() {
		List<ClientHttpRequestInterceptor> interceptors = getInterceptors(); // 获取拦截器
		if (!CollectionUtils.isEmpty(interceptors)) { // 有拦截器时创建请求工厂类InterceptingClientHttpRequestFactory
			ClientHttpRequestFactory factory = this.interceptingRequestFactory;
			if (factory == null) {
				factory = new InterceptingClientHttpRequestFactory(super.getRequestFactory(), interceptors); // 创建InterceptingClientHttpRequestFactory，并传入父类的请求工厂SimpleClientHttpRequestFactory
				this.interceptingRequestFactory = factory;
			}
			return factory;
		}
		else {
			return super.getRequestFactory(); // 没有拦截器时获取父类的请求工厂类SimpleClientHttpRequestFactory
		}
	}

}
