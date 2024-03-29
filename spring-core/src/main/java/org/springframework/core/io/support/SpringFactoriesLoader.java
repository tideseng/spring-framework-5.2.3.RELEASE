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

package org.springframework.core.io.support;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.io.UrlResource;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ConcurrentReferenceHashMap;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

/**
 * General purpose factory loading mechanism for internal use within the framework.
 *
 * <p>{@code SpringFactoriesLoader} {@linkplain #loadFactories loads} and instantiates
 * factories of a given type from {@value #FACTORIES_RESOURCE_LOCATION} files which
 * may be present in multiple JAR files in the classpath. The {@code spring.factories}
 * file must be in {@link Properties} format, where the key is the fully qualified
 * name of the interface or abstract class, and the value is a comma-separated list of
 * implementation class names. For example:
 *
 * <pre class="code">example.MyService=example.MyServiceImpl1,example.MyServiceImpl2</pre>
 *
 * where {@code example.MyService} is the name of the interface, and {@code MyServiceImpl1}
 * and {@code MyServiceImpl2} are two implementations.
 *
 * @author Arjen Poutsma
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @since 3.2
 */ // Spring应用或Spring Boot应用在启动时，会根据启动阶段不同的需求，调用SpringFactoriesLoader加载相应的工厂实例
public final class SpringFactoriesLoader { // SpringFactories加载器，读取classpath下所有的jar包中的META-INF/spring.factories配置文件，并获取其中定义的工厂类实例

	/**
	 * The location to look for factories.
	 * <p>Can be present in multiple JAR files.
	 */
	public static final String FACTORIES_RESOURCE_LOCATION = "META-INF/spring.factories"; // 定义从jar包中提取的工厂类的配置文件的相对路径（spring.factories文件是配置文件格式，每条属性的key必须是接口或者抽象类的全限定名，而属性值value是一个逗号分割的实现类的名称）


	private static final Log logger = LogFactory.getLog(SpringFactoriesLoader.class);

	private static final Map<ClassLoader, MultiValueMap<String, String>> cache = new ConcurrentReferenceHashMap<>(); // 本地缓存


	private SpringFactoriesLoader() {
	}


	/**
	 * Load and instantiate the factory implementations of the given type from
	 * {@value #FACTORIES_RESOURCE_LOCATION}, using the given class loader.
	 * <p>The returned factories are sorted through {@link AnnotationAwareOrderComparator}.
	 * <p>If a custom instantiation strategy is required, use {@link #loadFactoryNames}
	 * to obtain all registered factory names.
	 * @param factoryType the interface or abstract class representing the factory
	 * @param classLoader the ClassLoader to use for loading (can be {@code null} to use the default)
	 * @throws IllegalArgumentException if any factory implementation class cannot
	 * be loaded or if an error occurs while instantiating any factory
	 * @see #loadFactoryNames
	 */
	public static <T> List<T> loadFactories(Class<T> factoryType, @Nullable ClassLoader classLoader) { // 读取classpath下所有的jar包中的META-INF/spring.factories配置文件，获取其中定义的匹配类型 factoryClass的工厂类，然后创建每个工厂类的对象/实例，并返回这些工厂类对象/实例的列表
		Assert.notNull(factoryType, "'factoryType' must not be null");
		ClassLoader classLoaderToUse = classLoader;
		if (classLoaderToUse == null) { // 当传入的类加载器为空时，根据当前类获取类加载器
			classLoaderToUse = SpringFactoriesLoader.class.getClassLoader();
		}
		List<String> factoryImplementationNames = loadFactoryNames(factoryType, classLoaderToUse); // 获取相关工厂类的全路径类名
		if (logger.isTraceEnabled()) {
			logger.trace("Loaded [" + factoryType.getName() + "] names: " + factoryImplementationNames);
		}
		List<T> result = new ArrayList<>(factoryImplementationNames.size());
		for (String factoryImplementationName : factoryImplementationNames) { // 遍历工厂实现类名
			result.add(instantiateFactory(factoryImplementationName, factoryType, classLoaderToUse)); // 通过反射调用无参构造方法创建实例
		}
		AnnotationAwareOrderComparator.sort(result); // 排序
		return result;
	}

	/**
	 * Load the fully qualified class names of factory implementations of the
	 * given type from {@value #FACTORIES_RESOURCE_LOCATION}, using the given
	 * class loader.
	 * @param factoryType the interface or abstract class representing the factory
	 * @param classLoader the ClassLoader to use for loading resources; can be
	 * {@code null} to use the default
	 * @throws IllegalArgumentException if an error occurs while loading factory names
	 * @see #loadFactories
	 */
	public static List<String> loadFactoryNames(Class<?> factoryType, @Nullable ClassLoader classLoader) { // 获取相关工厂类的全路径类名（读取classpath下所有的jar包中的META-INF/spring.factories配置文件，获取其中定义的匹配类型 factoryClass的工厂类，然后返回这些工厂类的全路径列表）
		String factoryTypeName = factoryType.getName(); // 获取key
		return loadSpringFactories(classLoader).getOrDefault(factoryTypeName, Collections.emptyList()); // 从缓存结果中根据工厂名获取对应的工厂实现类名列表
	}

	private static Map<String, List<String>> loadSpringFactories(@Nullable ClassLoader classLoader) { // 加载classpath下所有的jar包中META-INF/spring.factories配置文件，合并封装到到本地缓存中并返回缓存结果
		MultiValueMap<String, String> result = cache.get(classLoader); // 先从缓存中获取数据，缓存中有时直接返回，防止重复加载
		if (result != null) { // 缓存存在时，直接返回
			return result;
		}
		// 缓存不存在，则加载classpath下所有的jar包中的META-INF/spring.factories配置文件
		try {
			Enumeration<URL> urls = (classLoader != null ?
					classLoader.getResources(FACTORIES_RESOURCE_LOCATION) : // 加载classpath下所有的jar包中的META-INF/spring.factories配置文件生成URL集合
					ClassLoader.getSystemResources(FACTORIES_RESOURCE_LOCATION));
			result = new LinkedMultiValueMap<>(); // 创建LinkedMultiValueMap容器，包装了LinkedHashMap容器
			while (urls.hasMoreElements()) { // 遍历URL集合
				URL url = urls.nextElement(); // 获取jar包中spring.factories文件的URL地址
				UrlResource resource = new UrlResource(url);
				Properties properties = PropertiesLoaderUtils.loadProperties(resource); // 将jar中具体的spring.factories文件地址解析成Properties类
				for (Map.Entry<?, ?> entry : properties.entrySet()) { // 遍历Properties属性
					String factoryTypeName = ((String) entry.getKey()).trim(); // 获取属性key，及工厂类的全路径名称
					for (String factoryImplementationName : StringUtils.commaDelimitedListToStringArray((String) entry.getValue())) { // 将属性值以逗号分隔成数组，并进行遍历
						result.add(factoryTypeName, factoryImplementationName.trim()); // 将工厂类名和工厂实现类名添加到LinkedMultiValueMap中（内部维护了一个 <String, List<String>> 结构的map，将值放入LinkedList集合中）
					}
				}
			}
			cache.put(classLoader, result); // 将结果放入缓存
			return result;
		}
		catch (IOException ex) {
			throw new IllegalArgumentException("Unable to load factories from location [" +
					FACTORIES_RESOURCE_LOCATION + "]", ex);
		}
	}

	@SuppressWarnings("unchecked")
	private static <T> T instantiateFactory(String factoryImplementationName, Class<T> factoryType, ClassLoader classLoader) { // 通过反射调用无参构造方法创建实例
		try {
			Class<?> factoryImplementationClass = ClassUtils.forName(factoryImplementationName, classLoader);
			if (!factoryType.isAssignableFrom(factoryImplementationClass)) { // 判断类型是否一致
				throw new IllegalArgumentException(
						"Class [" + factoryImplementationName + "] is not assignable to factory type [" + factoryType.getName() + "]");
			}
			return (T) ReflectionUtils.accessibleConstructor(factoryImplementationClass).newInstance(); // 通过反射调用无参构造方法创建实例
		}
		catch (Throwable ex) {
			throw new IllegalArgumentException(
				"Unable to instantiate factory class [" + factoryImplementationName + "] for factory type [" + factoryType.getName() + "]",
				ex);
		}
	}

}
