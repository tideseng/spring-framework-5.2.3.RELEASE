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

package org.springframework.context.config;

import org.w3c.dom.Element;

import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.xml.ParserContext;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.util.StringUtils;

/**
 * Parser for the {@code <context:property-placeholder/>} element.
 *
 * @author Juergen Hoeller
 * @author Dave Syer
 * @author Chris Beams
 * @since 2.5
 */
class PropertyPlaceholderBeanDefinitionParser extends AbstractPropertyLoadingBeanDefinitionParser { // property-placeholder标签对应的解析类，在ContextNamespaceHandler中进行创建

	private static final String SYSTEM_PROPERTIES_MODE_ATTRIBUTE = "system-properties-mode";

	private static final String SYSTEM_PROPERTIES_MODE_DEFAULT = "ENVIRONMENT";


	@Override
	@SuppressWarnings("deprecation")
	protected Class<?> getBeanClass(Element element) { // 获取element元素对应的BeanClass，封装到BeanDefinition中（钩子方法）
		// As of Spring 3.1, the default value of system-properties-mode has changed from
		// 'FALLBACK' to 'ENVIRONMENT'. This latter value indicates that resolution of
		// placeholders against system properties is a function of the Environment and
		// its current set of PropertySources.
		if (SYSTEM_PROPERTIES_MODE_DEFAULT.equals(element.getAttribute(SYSTEM_PROPERTIES_MODE_ATTRIBUTE))) {
			return PropertySourcesPlaceholderConfigurer.class; // 通过实现EnvironmentAware接口来整合Environment
		}

		// The user has explicitly specified a value for system-properties-mode: revert to
		// PropertyPlaceholderConfigurer to ensure backward compatibility with 3.0 and earlier.
		// This is deprecated; to be removed along with PropertyPlaceholderConfigurer itself.
		return org.springframework.beans.factory.config.PropertyPlaceholderConfigurer.class; // 该类已过时，只会从配置文件中解析占位符，不会从Environment中进行解析
	}

	@Override
	protected void doParse(Element element, ParserContext parserContext, BeanDefinitionBuilder builder) { // 解析方法，设置Element元素对应的BeanDefinition对象相关属性
		super.doParse(element, parserContext, builder); // 调用父类的解析方法设置BeanDefinition对象相关属性

		builder.addPropertyValue("ignoreUnresolvablePlaceholders",
				Boolean.valueOf(element.getAttribute("ignore-unresolvable")));

		String systemPropertiesModeName = element.getAttribute(SYSTEM_PROPERTIES_MODE_ATTRIBUTE); // 默认为ENVIRONMENT
		if (StringUtils.hasLength(systemPropertiesModeName) &&
				!systemPropertiesModeName.equals(SYSTEM_PROPERTIES_MODE_DEFAULT)) {
			builder.addPropertyValue("systemPropertiesModeName", "SYSTEM_PROPERTIES_MODE_" + systemPropertiesModeName);
		}

		if (element.hasAttribute("value-separator")) {
			builder.addPropertyValue("valueSeparator", element.getAttribute("value-separator")); // 占位分隔符，默认为:
		}
		if (element.hasAttribute("trim-values")) {
			builder.addPropertyValue("trimValues", element.getAttribute("trim-values"));
		}
		if (element.hasAttribute("null-value")) {
			builder.addPropertyValue("nullValue", element.getAttribute("null-value"));
		}
	}

}
