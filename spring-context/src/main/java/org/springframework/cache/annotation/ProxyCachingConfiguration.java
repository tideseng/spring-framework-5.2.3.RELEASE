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

package org.springframework.cache.annotation;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.cache.config.CacheManagementConfigUtils;
import org.springframework.cache.interceptor.BeanFactoryCacheOperationSourceAdvisor;
import org.springframework.cache.interceptor.CacheInterceptor;
import org.springframework.cache.interceptor.CacheOperationSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;

/**
 * {@code @Configuration} class that registers the Spring infrastructure beans necessary
 * to enable proxy-based annotation-driven cache management.
 *
 * @author Chris Beams
 * @author Juergen Hoeller
 * @since 3.1
 * @see EnableCaching
 * @see CachingConfigurationSelector
 */
@Configuration
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
public class ProxyCachingConfiguration extends AbstractCachingConfiguration {

	@Bean(name = CacheManagementConfigUtils.CACHE_ADVISOR_BEAN_NAME)
	@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
	public BeanFactoryCacheOperationSourceAdvisor cacheAdvisor() { // 注入缓存切面类
		BeanFactoryCacheOperationSourceAdvisor advisor = new BeanFactoryCacheOperationSourceAdvisor(); // 创建缓存切面类BeanFactoryCacheOperationSourceAdvisor
		advisor.setCacheOperationSource(cacheOperationSource()); // 设置缓存操作处理器
		advisor.setAdvice(cacheInterceptor()); // 设置切面的Advice
		if (this.enableCaching != null) { // 根据@EnableCaching注解信息设置切面排序
			advisor.setOrder(this.enableCaching.<Integer>getNumber("order")); // 设置切面排序
		}
		return advisor;
	}

	@Bean
	@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
	public CacheOperationSource cacheOperationSource() { // 注入缓存操作处理器
		return new AnnotationCacheOperationSource(); // 创建缓存操作处理器（解析@Cacheable、@CacheEvict、@CachePut、@Caching缓存注解并封装成CacheOperation对象）
	}

	@Bean
	@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
	public CacheInterceptor cacheInterceptor() { // 注入缓存切面的Advice
		CacheInterceptor interceptor = new CacheInterceptor(); // 创建缓存切面增强，实现了MethodInterceptor接口
		interceptor.configure(this.errorHandler, this.keyGenerator, this.cacheResolver, this.cacheManager); // 配置errorHandler、keyGenerator、cacheResolver相关属性
		interceptor.setCacheOperationSource(cacheOperationSource()); // 设置缓存操作处理器
		return interceptor;
	}

}
