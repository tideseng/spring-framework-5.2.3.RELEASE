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

package org.springframework.context.support;

import java.io.IOException;
import java.util.Properties;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.PlaceholderConfigurerSupport;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.ConfigurablePropertyResolver;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.StringValueResolver;

/**
 * Specialization of {@link PlaceholderConfigurerSupport} that resolves ${...} placeholders
 * within bean definition property values and {@code @Value} annotations against the current
 * Spring {@link Environment} and its set of {@link PropertySources}.
 *
 * <p>This class is designed as a general replacement for {@code PropertyPlaceholderConfigurer}.
 * It is used by default to support the {@code property-placeholder} element in working against
 * the spring-context-3.1 or higher XSD; whereas, spring-context versions &lt;= 3.0 default to
 * {@code PropertyPlaceholderConfigurer} to ensure backward compatibility. See the spring-context
 * XSD documentation for complete details.
 *
 * <p>Any local properties (e.g. those added via {@link #setProperties}, {@link #setLocations}
 * et al.) are added as a {@code PropertySource}. Search precedence of local properties is
 * based on the value of the {@link #setLocalOverride localOverride} property, which is by
 * default {@code false} meaning that local properties are to be searched last, after all
 * environment property sources.
 *
 * <p>See {@link org.springframework.core.env.ConfigurableEnvironment} and related javadocs
 * for details on manipulating environment property sources.
 *
 * @author Chris Beams
 * @author Juergen Hoeller
 * @since 3.1
 * @see org.springframework.core.env.ConfigurableEnvironment
 * @see org.springframework.beans.factory.config.PlaceholderConfigurerSupport
 * @see org.springframework.beans.factory.config.PropertyPlaceholderConfigurer
 */
public class PropertySourcesPlaceholderConfigurer extends PlaceholderConfigurerSupport implements EnvironmentAware { // 实现了BeanFactoryPostProcessor接口，通过postProcessBeanFactory方法解析参数；实现了EnvironmentAware接口来整合Environment

	/**
	 * {@value} is the name given to the {@link PropertySource} for the set of
	 * {@linkplain #mergeProperties() merged properties} supplied to this configurer.
	 */
	public static final String LOCAL_PROPERTIES_PROPERTY_SOURCE_NAME = "localProperties";

	/**
	 * {@value} is the name given to the {@link PropertySource} that wraps the
	 * {@linkplain #setEnvironment environment} supplied to this configurer.
	 */
	public static final String ENVIRONMENT_PROPERTIES_PROPERTY_SOURCE_NAME = "environmentProperties";


	@Nullable
	private MutablePropertySources propertySources; // 该类的结构与StandardEnvironment类相似，都有一个MutablePropertySources

	@Nullable
	private PropertySources appliedPropertySources;

	@Nullable
	private Environment environment;


	/**
	 * Customize the set of {@link PropertySources} to be used by this configurer.
	 * <p>Setting this property indicates that environment property sources and
	 * local properties should be ignored.
	 * @see #postProcessBeanFactory
	 */
	public void setPropertySources(PropertySources propertySources) {
		this.propertySources = new MutablePropertySources(propertySources);
	}

	/**
	 * {@code PropertySources} from the given {@link Environment}
	 * will be searched when replacing ${...} placeholders.
	 * @see #setPropertySources
	 * @see #postProcessBeanFactory
	 */
	@Override
	public void setEnvironment(Environment environment) {
		this.environment = environment;
	}


	/**
	 * Processing occurs by replacing ${...} placeholders in bean definitions by resolving each
	 * against this configurer's set of {@link PropertySources}, which includes:
	 * <ul>
	 * <li>all {@linkplain org.springframework.core.env.ConfigurableEnvironment#getPropertySources
	 * environment property sources}, if an {@code Environment} {@linkplain #setEnvironment is present}
	 * <li>{@linkplain #mergeProperties merged local properties}, if {@linkplain #setLocation any}
	 * {@linkplain #setLocations have} {@linkplain #setProperties been}
	 * {@linkplain #setPropertiesArray specified}
	 * <li>any property sources set by calling {@link #setPropertySources}
	 * </ul>
	 * <p>If {@link #setPropertySources} is called, <strong>environment and local properties will be
	 * ignored</strong>. This method is designed to give the user fine-grained control over property
	 * sources, and once set, the configurer makes no assumptions about adding additional sources.
	 */
	@Override
	public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException { // BeanFactoryPostProcessor接口方法，执行参数解析
		if (this.propertySources == null) {
			this.propertySources = new MutablePropertySources(); // 创建MutablePropertySources对象，用propertySourceList列表来存放属性来源途径（Environment和本地配置文件）
			if (this.environment != null) {
				this.propertySources.addLast( // 1.添加Environment属性来源
					new PropertySource<Environment>(ENVIRONMENT_PROPERTIES_PROPERTY_SOURCE_NAME, this.environment) { // 通过PropertySource构造方法注入Environment
						@Override
						@Nullable
						public String getProperty(String key) { // 根据属性key获取属性值
							return this.source.getProperty(key); // 这里的source是Environment对象，表示从Environment中获取属性值
						}
					}
				);
			}
			try {
				PropertySource<?> localPropertySource =
						new PropertiesPropertySource(LOCAL_PROPERTIES_PROPERTY_SOURCE_NAME, mergeProperties()); // 加载本地配置文件中的属性值并包装成Properties对象，再设置到PropertiesPropertySource对象中
				if (this.localOverride) {
					this.propertySources.addFirst(localPropertySource); // 2.添加本地配置文件属性来源
				}
				else {
					this.propertySources.addLast(localPropertySource); // 2.添加本地配置文件属性来源
				}
			}
			catch (IOException ex) {
				throw new BeanInitializationException("Could not load properties", ex);
			}
		}

		processProperties(beanFactory, new PropertySourcesPropertyResolver(this.propertySources)); // 创建解析类并处理
		this.appliedPropertySources = this.propertySources;
	}

	/**
	 * Visit each bean definition in the given bean factory and attempt to replace ${...} property
	 * placeholders with values from the given properties.
	 */
	protected void processProperties(ConfigurableListableBeanFactory beanFactoryToProcess,
			final ConfigurablePropertyResolver propertyResolver) throws BeansException {
		// 设置占位符的前后缀
		propertyResolver.setPlaceholderPrefix(this.placeholderPrefix);
		propertyResolver.setPlaceholderSuffix(this.placeholderSuffix);
		propertyResolver.setValueSeparator(this.valueSeparator); // 设置分隔符

		StringValueResolver valueResolver = strVal -> { // 函数式接口，resolveStringValue方法的回调实现
			String resolved = (this.ignoreUnresolvablePlaceholders ?
					propertyResolver.resolvePlaceholders(strVal) :
					propertyResolver.resolveRequiredPlaceholders(strVal));
			if (this.trimValues) {
				resolved = resolved.trim();
			}
			return (resolved.equals(this.nullValue) ? null : resolved);
		};

		doProcessProperties(beanFactoryToProcess, valueResolver);
	}

	/**
	 * Implemented for compatibility with
	 * {@link org.springframework.beans.factory.config.PlaceholderConfigurerSupport}.
	 * @deprecated in favor of
	 * {@link #processProperties(ConfigurableListableBeanFactory, ConfigurablePropertyResolver)}
	 * @throws UnsupportedOperationException in this implementation
	 */
	@Override
	@Deprecated
	protected void processProperties(ConfigurableListableBeanFactory beanFactory, Properties props) {
		throw new UnsupportedOperationException(
				"Call processProperties(ConfigurableListableBeanFactory, ConfigurablePropertyResolver) instead");
	}

	/**
	 * Return the property sources that were actually applied during
	 * {@link #postProcessBeanFactory(ConfigurableListableBeanFactory) post-processing}.
	 * @return the property sources that were applied
	 * @throws IllegalStateException if the property sources have not yet been applied
	 * @since 4.0
	 */
	public PropertySources getAppliedPropertySources() throws IllegalStateException {
		Assert.state(this.appliedPropertySources != null, "PropertySources have not yet been applied");
		return this.appliedPropertySources;
	}

}
