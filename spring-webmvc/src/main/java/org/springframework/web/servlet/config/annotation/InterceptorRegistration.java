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

package org.springframework.web.servlet.config.annotation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.PathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.handler.MappedInterceptor;

/**
 * Assists with the creation of a {@link MappedInterceptor}.
 *
 * @author Rossen Stoyanchev
 * @author Keith Donald
 * @since 3.1
 */
public class InterceptorRegistration { // 拦截器包装类

	private final HandlerInterceptor interceptor; // 真实的拦截器对象

	private final List<String> includePatterns = new ArrayList<>(); // 拦截器URL匹配规则

	private final List<String> excludePatterns = new ArrayList<>(); // 拦截器URL放行规则

	@Nullable
	private PathMatcher pathMatcher; // 路径匹配器

	private int order = 0;


	/**
	 * Create an {@link InterceptorRegistration} instance.
	 */
	public InterceptorRegistration(HandlerInterceptor interceptor) { // 初始化InterceptorRegistration
		Assert.notNull(interceptor, "Interceptor is required");
		this.interceptor = interceptor;
	}


	/**
	 * Add URL patterns to which the registered interceptor should apply to.
	 */
	public InterceptorRegistration addPathPatterns(String... patterns) { // 添加拦截器URL匹配规则
		return addPathPatterns(Arrays.asList(patterns)); // 添加拦截器URL匹配规则
	}

	/**
	 * List-based variant of {@link #addPathPatterns(String...)}.
	 * @since 5.0.3
	 */
	public InterceptorRegistration addPathPatterns(List<String> patterns) { // 添加拦截器URL匹配规则
		this.includePatterns.addAll(patterns);
		return this;
	}

	/**
	 * Add URL patterns to which the registered interceptor should not apply to.
	 */
	public InterceptorRegistration excludePathPatterns(String... patterns) { // 添加拦截器URL放行规则
		return excludePathPatterns(Arrays.asList(patterns));
	}

	/**
	 * List-based variant of {@link #excludePathPatterns(String...)}.
	 * @since 5.0.3
	 */
	public InterceptorRegistration excludePathPatterns(List<String> patterns) { // 添加拦截器URL放行规则
		this.excludePatterns.addAll(patterns);
		return this;
	}

	/**
	 * A PathMatcher implementation to use with this interceptor. This is an optional,
	 * advanced property required only if using custom PathMatcher implementations
	 * that support mapping metadata other than the Ant path patterns supported
	 * by default.
	 */
	public InterceptorRegistration pathMatcher(PathMatcher pathMatcher) {
		this.pathMatcher = pathMatcher;
		return this;
	}

	/**
	 * Specify an order position to be used. Default is 0.
	 * @since 4.3.23
	 */
	public InterceptorRegistration order(int order){ // 指定排序
		this.order = order;
		return this;
	}

	/**
	 * Return the order position to be used.
	 */
	protected int getOrder() {
		return this.order;
	}

	/**
	 * Build the underlying interceptor. If URL patterns are provided, the returned
	 * type is {@link MappedInterceptor}; otherwise {@link HandlerInterceptor}.
	 */
	protected Object getInterceptor() { // 获取当前拦截器（返回的可能是真实的拦截器，也可能是包装类MappedInterceptor）
		if (this.includePatterns.isEmpty() && this.excludePatterns.isEmpty()) { // 当URL匹配规则和URL匹配规则都为空时，直接返回真实的拦截器
			return this.interceptor;
		}

		String[] include = StringUtils.toStringArray(this.includePatterns);
		String[] exclude = StringUtils.toStringArray(this.excludePatterns);
		MappedInterceptor mappedInterceptor = new MappedInterceptor(include, exclude, this.interceptor); // 将拦截器真实对象、URL匹配规则、URL放行规则封装到MappedInterceptor中（MappedInterceptor实现了HandlerInterceptor接口）
		if (this.pathMatcher != null) {
			mappedInterceptor.setPathMatcher(this.pathMatcher);
		}
		return mappedInterceptor;
	}

}
