/*
 * Copyright 2002-2017 the original author or authors.
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

package org.springframework.cache.support;

import java.util.Collection;
import java.util.Collections;

import org.springframework.cache.Cache;

/**
 * Simple cache manager working against a given collection of caches.
 * Useful for testing or simple caching declarations.
 *
 * @author Costin Leau
 * @since 3.1
 */
public class SimpleCacheManager extends AbstractCacheManager { // 缓存管理器基本实现，将当前应用的Cache实现类交给Spring来管理

	private Collection<? extends Cache> caches = Collections.emptySet(); // 存放Cache缓存实现类的容器（从外部设置，并交给Spring内部使用）


	/**
	 * Specify the collection of Cache instances to use for this CacheManager.
	 */
	public void setCaches(Collection<? extends Cache> caches) { // 设置Cache缓存（外部调用）
		this.caches = caches;
	}

	@Override
	protected Collection<? extends Cache> loadCaches() { // 获取Cache缓存（由Spring调用）
		return this.caches;
	}

}
