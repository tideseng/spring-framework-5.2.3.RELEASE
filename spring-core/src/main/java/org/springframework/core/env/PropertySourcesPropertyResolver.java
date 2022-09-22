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

package org.springframework.core.env;

import org.springframework.lang.Nullable;

/**
 * {@link PropertyResolver} implementation that resolves property values against
 * an underlying set of {@link PropertySources}.
 *
 * @author Chris Beams
 * @author Juergen Hoeller
 * @since 3.1
 * @see PropertySource
 * @see PropertySources
 * @see AbstractEnvironment
 */
public class PropertySourcesPropertyResolver extends AbstractPropertyResolver { // 属性解析器，通过注入的属性源解析属性

	@Nullable
	private final PropertySources propertySources; // 属性源，默认为MutablePropertySources类


	/**
	 * Create a new resolver against the given property sources.
	 * @param propertySources the set of {@link PropertySource} objects to use
	 */
	public PropertySourcesPropertyResolver(@Nullable PropertySources propertySources) { // 注入属性源，默认为MutablePropertySources类
		this.propertySources = propertySources;
	}


	@Override
	public boolean containsProperty(String key) { // 判断是否包含属性
		if (this.propertySources != null) {
			for (PropertySource<?> propertySource : this.propertySources) {
				if (propertySource.containsProperty(key)) {
					return true;
				}
			}
		}
		return false;
	}

	@Override
	@Nullable
	public String getProperty(String key) { // 获取属性，并进一步解析具有占位符的属性值
		return getProperty(key, String.class, true);
	}

	@Override
	@Nullable
	public <T> T getProperty(String key, Class<T> targetValueType) { // 获取属性，并进一步解析具有占位符的属性值
		return getProperty(key, targetValueType, true);
	}

	@Override
	@Nullable
	protected String getPropertyAsRawString(String key) { // 获取属性，并进一步解析具有占位符的属性值
		return getProperty(key, String.class, false);
	}

	@Nullable
	protected <T> T getProperty(String key, Class<T> targetValueType, boolean resolveNestedPlaceholders) { // 获取属性
		if (this.propertySources != null) {
			for (PropertySource<?> propertySource : this.propertySources) { // 遍历属性源PropertySource，默认会调用MutablePropertySources#iterator方法进行遍历
				if (logger.isTraceEnabled()) {
					logger.trace("Searching for key '" + key + "' in PropertySource '" +
							propertySource.getName() + "'");
				}
				Object value = propertySource.getProperty(key); // 根据属性名获取属性值
				if (value != null) {
					if (resolveNestedPlaceholders && value instanceof String) { // 根据情况进一步解析具有占位符的属性值
						value = resolveNestedPlaceholders((String) value);
					}
					logKeyFound(key, propertySource, value); // 打印日志
					return convertValueIfNecessary(value, targetValueType); // 将属性值转为指定类型
				}
			}
		}
		if (logger.isTraceEnabled()) {
			logger.trace("Could not find key '" + key + "' in any property source");
		}
		return null; // 获取不到时返回null
	}

	/**
	 * Log the given key as found in the given {@link PropertySource}, resulting in
	 * the given value.
	 * <p>The default implementation writes a debug log message with key and source.
	 * As of 4.3.3, this does not log the value anymore in order to avoid accidental
	 * logging of sensitive settings. Subclasses may override this method to change
	 * the log level and/or log message, including the property's value if desired.
	 * @param key the key found
	 * @param propertySource the {@code PropertySource} that the key has been found in
	 * @param value the corresponding value
	 * @since 4.3.1
	 */
	protected void logKeyFound(String key, PropertySource<?> propertySource, Object value) {
		if (logger.isDebugEnabled()) {
			logger.debug("Found key '" + key + "' in PropertySource '" + propertySource.getName() +
					"' with value of type " + value.getClass().getSimpleName());
		}
	}

}
