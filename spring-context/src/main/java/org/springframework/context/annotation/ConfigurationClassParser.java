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

package org.springframework.context.annotation;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.parsing.Location;
import org.springframework.beans.factory.parsing.Problem;
import org.springframework.beans.factory.parsing.ProblemReporter;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionReader;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.context.annotation.ConfigurationCondition.ConfigurationPhase;
import org.springframework.context.annotation.DeferredImportSelector.Group;
import org.springframework.core.NestedIOException;
import org.springframework.core.OrderComparator;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.env.CompositePropertySource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.DefaultPropertySourceFactory;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.core.io.support.PropertySourceFactory;
import org.springframework.core.io.support.ResourcePropertySource;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.MethodMetadata;
import org.springframework.core.type.StandardAnnotationMetadata;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.CollectionUtils;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;

/**
 * Parses a {@link Configuration} class definition, populating a collection of
 * {@link ConfigurationClass} objects (parsing a single Configuration class may result in
 * any number of ConfigurationClass objects because one Configuration class may import
 * another using the {@link Import} annotation).
 *
 * <p>This class helps separate the concern of parsing the structure of a Configuration
 * class from the concern of registering BeanDefinition objects based on the content of
 * that model (with the exception of {@code @ComponentScan} annotations which need to be
 * registered immediately).
 *
 * <p>This ASM-based implementation avoids reflection and eager class loading in order to
 * interoperate effectively with lazy class loading in a Spring ApplicationContext.
 *
 * @author Chris Beams
 * @author Juergen Hoeller
 * @author Phillip Webb
 * @author Sam Brannen
 * @author Stephane Nicoll
 * @since 3.0
 * @see ConfigurationClassBeanDefinitionReader
 */
class ConfigurationClassParser {

	private static final PropertySourceFactory DEFAULT_PROPERTY_SOURCE_FACTORY = new DefaultPropertySourceFactory();

	private static final Comparator<DeferredImportSelectorHolder> DEFERRED_IMPORT_COMPARATOR =
			(o1, o2) -> AnnotationAwareOrderComparator.INSTANCE.compare(o1.getImportSelector(), o2.getImportSelector());


	private final Log logger = LogFactory.getLog(getClass());

	private final MetadataReaderFactory metadataReaderFactory;

	private final ProblemReporter problemReporter;

	private final Environment environment;

	private final ResourceLoader resourceLoader;

	private final BeanDefinitionRegistry registry;

	private final ComponentScanAnnotationParser componentScanParser;

	private final ConditionEvaluator conditionEvaluator;

	private final Map<ConfigurationClass, ConfigurationClass> configurationClasses = new LinkedHashMap<>();

	private final Map<String, ConfigurationClass> knownSuperclasses = new HashMap<>();

	private final List<String> propertySourceNames = new ArrayList<>();

	private final ImportStack importStack = new ImportStack();

	private final DeferredImportSelectorHandler deferredImportSelectorHandler = new DeferredImportSelectorHandler(); //  默认创建DeferredImportSelector处理器

	private final SourceClass objectSourceClass = new SourceClass(Object.class);


	/**
	 * Create a new {@link ConfigurationClassParser} instance that will be used
	 * to populate the set of configuration classes.
	 */
	public ConfigurationClassParser(MetadataReaderFactory metadataReaderFactory, // 初始化ConfigurationClassParser配置类解析器
			ProblemReporter problemReporter, Environment environment, ResourceLoader resourceLoader,
			BeanNameGenerator componentScanBeanNameGenerator, BeanDefinitionRegistry registry) {

		this.metadataReaderFactory = metadataReaderFactory;
		this.problemReporter = problemReporter;
		this.environment = environment;
		this.resourceLoader = resourceLoader;
		this.registry = registry;
		this.componentScanParser = new ComponentScanAnnotationParser( // 创建ComponentScanAnnotationParser
				environment, resourceLoader, componentScanBeanNameGenerator, registry);
		this.conditionEvaluator = new ConditionEvaluator(registry, environment, resourceLoader);
	}


	public void parse(Set<BeanDefinitionHolder> configCandidates) { // 解析候选配置类BeanDefinition集合
		for (BeanDefinitionHolder holder : configCandidates) { // 遍历配置类BeanDefinition，并进行解析
			BeanDefinition bd = holder.getBeanDefinition();
			try {
				if (bd instanceof AnnotatedBeanDefinition) { // 如果是注解扫描产生的BeanDefinition（如：ScannedGenericBeanDefinition）
					parse(((AnnotatedBeanDefinition) bd).getMetadata(), holder.getBeanName()); // 解析AnnotatedBeanDefinition配置类
				}
				else if (bd instanceof AbstractBeanDefinition && ((AbstractBeanDefinition) bd).hasBeanClass()) { // 如果是非注解扫描产生的BeanDefinition（如：RootBeanDefinition、GenericBeanDefinition）
					parse(((AbstractBeanDefinition) bd).getBeanClass(), holder.getBeanName()); // 解析AbstractBeanDefinition配置类
				}
				else { // 其它情况
					parse(bd.getBeanClassName(), holder.getBeanName());
				}
			}
			catch (BeanDefinitionStoreException ex) {
				throw ex;
			}
			catch (Throwable ex) {
				throw new BeanDefinitionStoreException(
						"Failed to parse configuration class [" + bd.getBeanClassName() + "]", ex);
			}
		}

		this.deferredImportSelectorHandler.process(); // 处理添加的DeferredImportSelector的列表，即DeferredImportSelector接口方法是在parse方法的最后才会执行
	}

	protected final void parse(@Nullable String className, String beanName) throws IOException { // 解析配置类
		Assert.notNull(className, "No bean class name for configuration class bean definition");
		MetadataReader reader = this.metadataReaderFactory.getMetadataReader(className);
		processConfigurationClass(new ConfigurationClass(reader, beanName)); // 将参数封装为ConfigurationClass，并进行解析
	}

	protected final void parse(Class<?> clazz, String beanName) throws IOException { // 解析AbstractBeanDefinition配置类
		processConfigurationClass(new ConfigurationClass(clazz, beanName)); // 将参数封装为ConfigurationClass，并进行解析
	}

	protected final void parse(AnnotationMetadata metadata, String beanName) throws IOException { // 解析AnnotatedBeanDefinition配置类
		processConfigurationClass(new ConfigurationClass(metadata, beanName)); // 将参数封装为ConfigurationClass，并进行解析
	}

	/**
	 * Validate each {@link ConfigurationClass} object.
	 * @see ConfigurationClass#validate
	 */
	public void validate() {
		for (ConfigurationClass configClass : this.configurationClasses.keySet()) {
			configClass.validate(this.problemReporter);
		}
	}

	public Set<ConfigurationClass> getConfigurationClasses() {
		return this.configurationClasses.keySet();
	}


	protected void processConfigurationClass(ConfigurationClass configClass) throws IOException { // 解析配置类（不管是什么类型的BeanDefinition都会调用此方法）
		if (this.conditionEvaluator.shouldSkip(configClass.getMetadata(), ConfigurationPhase.PARSE_CONFIGURATION)) { // 根据@Conditional条件注解决定是否跳过处理配置类
			return;
		}

		ConfigurationClass existingClass = this.configurationClasses.get(configClass);
		if (existingClass != null) {
			if (configClass.isImported()) {
				if (existingClass.isImported()) {
					existingClass.mergeImportedBy(configClass);
				}
				// Otherwise ignore new imported config class; existing non-imported class overrides it.
				return;
			}
			else {
				// Explicit bean definition found, probably replacing an import.
				// Let's remove the old one and go with the new one.
				this.configurationClasses.remove(configClass);
				this.knownSuperclasses.values().removeIf(configClass::equals);
			}
		}

		// Recursively process the configuration class and its superclass hierarchy.
		SourceClass sourceClass = asSourceClass(configClass); // 将ConfigurationClass封装到SourceClass中
		do { // do-while循环体，只有当返回的SourceClass为null时跳出循环
			sourceClass = doProcessConfigurationClass(configClass, sourceClass); // 处理解析配置类的具体逻辑
		}
		while (sourceClass != null);

		this.configurationClasses.put(configClass, configClass); // 放入configurationClasses映射容器
	}

	/**
	 * Apply processing and build a complete {@link ConfigurationClass} by reading the
	 * annotations, members and methods from the source class. This method can be called
	 * multiple times as relevant sources are discovered.
	 * @param configClass the configuration class being build
	 * @param sourceClass a source class
	 * @return the superclass, or {@code null} if none found or previously processed
	 */
	@Nullable
	protected final SourceClass doProcessConfigurationClass(ConfigurationClass configClass, SourceClass sourceClass) // 处理解析配置类的具体逻辑
			throws IOException {
		// 一、判断配置类上面是否有@Component注解，如果有则递归解析轻量级配置内部类
		if (configClass.getMetadata().isAnnotated(Component.class.getName())) {
			// Recursively process any member (nested) classes first
			processMemberClasses(configClass, sourceClass); // 递归解析有@Component相关注解的内部配置类，会递归调用ConfigurationClassParser#processConfigurationClass(...)方法
		}
		// 二、处理配置类上有@PropertySources、@PropertySource的注解
		// Process any @PropertySource annotations
		for (AnnotationAttributes propertySource : AnnotationConfigUtils.attributesForRepeatable(
				sourceClass.getMetadata(), PropertySources.class,
				org.springframework.context.annotation.PropertySource.class)) {
			if (this.environment instanceof ConfigurableEnvironment) {
				processPropertySource(propertySource); // 处理@PropertySource注解
			}
			else {
				logger.info("Ignoring @PropertySource annotation on [" + sourceClass.getMetadata().getClassName() +
						"]. Reason: Environment must implement ConfigurableEnvironment");
			}
		}
		// 三、处理配置类上有@ComponentScans、@ComponentScan的注解
		// Process any @ComponentScan annotations
		Set<AnnotationAttributes> componentScans = AnnotationConfigUtils.attributesForRepeatable(
				sourceClass.getMetadata(), ComponentScans.class, ComponentScan.class);
		if (!componentScans.isEmpty() &&
				!this.conditionEvaluator.shouldSkip(sourceClass.getMetadata(), ConfigurationPhase.REGISTER_BEAN)) { // 判断是否需要跳过
			for (AnnotationAttributes componentScan : componentScans) { // 遍历@ComponentScan注解属性
				// The config class is annotated with @ComponentScan -> perform the scan immediately
				Set<BeanDefinitionHolder> scannedBeanDefinitions =
						this.componentScanParser.parse(componentScan, sourceClass.getMetadata().getClassName()); // 处理@componentScan注解，扫描生成符合条件的BeanDefinition集合
				// Check the set of scanned definitions for any further config classes and parse recursively if needed
				for (BeanDefinitionHolder holder : scannedBeanDefinitions) { // 遍历扫描到的BeanDefinition
					BeanDefinition bdCand = holder.getBeanDefinition().getOriginatingBeanDefinition();
					if (bdCand == null) {
						bdCand = holder.getBeanDefinition();
					}
					if (ConfigurationClassUtils.checkConfigurationClassCandidate(bdCand, this.metadataReaderFactory)) { // 判断BeanDefinition是否为候选配置类
						parse(bdCand.getBeanClassName(), holder.getBeanName()); // 如果是则递归调用ConfigurationClassParser#parse(...)方法
					}
				}
			}
		}
		// 四、处理配置类上有@Import的注解
		// Process any @Import annotations
		processImports(configClass, sourceClass, getImports(sourceClass), true); // 获取@Import注解导入的类并封装成SourceClass，再处理配置类上有@Import的注解（这里是Spring Boot自动装配导入类AutoConfigurationImportSelector的实例化入口）
		// 五、处理配置类上有@ImportResource的注解
		// Process any @ImportResource annotations
		AnnotationAttributes importResource = // 获取@ImportResource注解信息
				AnnotationConfigUtils.attributesFor(sourceClass.getMetadata(), ImportResource.class);
		if (importResource != null) { // 当@ImportResource注解信息存在时
			String[] resources = importResource.getStringArray("locations");
			Class<? extends BeanDefinitionReader> readerClass = importResource.getClass("reader");
			for (String resource : resources) { // 遍历location
				String resolvedResource = this.environment.resolveRequiredPlaceholders(resource); // 解析占位符
				configClass.addImportedResource(resolvedResource, readerClass);
			}
		}
		// 六、处理方法上有@Bean的注解
		// Process individual @Bean methods
		Set<MethodMetadata> beanMethods = retrieveBeanMethodMetadata(sourceClass); // 获取所有方法上有@Bean注解的元信息
		for (MethodMetadata methodMetadata : beanMethods) { // 遍历元信息
			configClass.addBeanMethod(new BeanMethod(methodMetadata, configClass)); // 创建BeanMethod，并添加到ConfigurationClass的@Bean方法容器
		}

		// Process default methods on interfaces
		processInterfaces(configClass, sourceClass);

		// Process superclass, if any
		if (sourceClass.getMetadata().hasSuperClass()) { // 如果当前类有父类（默认的父类为java.lang.Object）
			String superclass = sourceClass.getMetadata().getSuperClassName(); // 获取父类class
			if (superclass != null && !superclass.startsWith("java") && // 当父类存在且不是jdk原生类且没被处理时
					!this.knownSuperclasses.containsKey(superclass)) {
				this.knownSuperclasses.put(superclass, configClass);
				// Superclass found, return its annotation metadata and recurse
				return sourceClass.getSuperClass(); // 返回父类，作为配置类进行解析
			}
		}

		// No superclass -> processing is complete
		return null;
	}

	/**
	 * Register member (nested) classes that happen to be configuration classes themselves.
	 */
	private void processMemberClasses(ConfigurationClass configClass, SourceClass sourceClass) throws IOException { // 递归解析有@Component相关注解的内部配置类
		Collection<SourceClass> memberClasses = sourceClass.getMemberClasses(); // 获取当前SourceClass的内部类集合
		if (!memberClasses.isEmpty()) { // 当内部类存在时，添加轻量级配置类、再进行排序、最后递归调用
			List<SourceClass> candidates = new ArrayList<>(memberClasses.size());
			for (SourceClass memberClass : memberClasses) { // 遍历内部类对应的SourceClass
				if (ConfigurationClassUtils.isConfigurationCandidate(memberClass.getMetadata()) && // 如果类是否为轻量级配置类
						!memberClass.getMetadata().getClassName().equals(configClass.getMetadata().getClassName())) {
					candidates.add(memberClass);
				}
			}
			OrderComparator.sort(candidates); // 进行排序
			for (SourceClass candidate : candidates) { // 遍历内部类（candidate是configClass的内部类）
				if (this.importStack.contains(configClass)) {
					this.problemReporter.error(new CircularImportProblem(configClass, this.importStack));
				}
				else {
					this.importStack.push(configClass);
					try {
						processConfigurationClass(candidate.asConfigClass(configClass)); // 解析内部类的内部类，会递归调用ConfigurationClassParser#processConfigurationClass(...)方法
					}
					finally {
						this.importStack.pop();
					}
				}
			}
		}
	}

	/**
	 * Register default methods on interfaces implemented by the configuration class.
	 */
	private void processInterfaces(ConfigurationClass configClass, SourceClass sourceClass) throws IOException {
		for (SourceClass ifc : sourceClass.getInterfaces()) {
			Set<MethodMetadata> beanMethods = retrieveBeanMethodMetadata(ifc);
			for (MethodMetadata methodMetadata : beanMethods) {
				if (!methodMetadata.isAbstract()) {
					// A default method or other concrete method on a Java 8+ interface...
					configClass.addBeanMethod(new BeanMethod(methodMetadata, configClass));
				}
			}
			processInterfaces(configClass, ifc);
		}
	}

	/**
	 * Retrieve the metadata for all <code>@Bean</code> methods.
	 */
	private Set<MethodMetadata> retrieveBeanMethodMetadata(SourceClass sourceClass) { // 获取所有方法上有@Bean注解的元信息
		AnnotationMetadata original = sourceClass.getMetadata();
		Set<MethodMetadata> beanMethods = original.getAnnotatedMethods(Bean.class.getName()); // 获取带有@Bean注解方法的元信息
		if (beanMethods.size() > 1 && original instanceof StandardAnnotationMetadata) {
			// Try reading the class file via ASM for deterministic declaration order...
			// Unfortunately, the JVM's standard reflection returns methods in arbitrary
			// order, even between different runs of the same application on the same JVM.
			try {
				AnnotationMetadata asm =
						this.metadataReaderFactory.getMetadataReader(original.getClassName()).getAnnotationMetadata();
				Set<MethodMetadata> asmMethods = asm.getAnnotatedMethods(Bean.class.getName());
				if (asmMethods.size() >= beanMethods.size()) {
					Set<MethodMetadata> selectedMethods = new LinkedHashSet<>(asmMethods.size());
					for (MethodMetadata asmMethod : asmMethods) {
						for (MethodMetadata beanMethod : beanMethods) {
							if (beanMethod.getMethodName().equals(asmMethod.getMethodName())) {
								selectedMethods.add(beanMethod);
								break;
							}
						}
					}
					if (selectedMethods.size() == beanMethods.size()) {
						// All reflection-detected methods found in ASM method set -> proceed
						beanMethods = selectedMethods;
					}
				}
			}
			catch (IOException ex) {
				logger.debug("Failed to read class file via ASM for determining @Bean method order", ex);
				// No worries, let's continue with the reflection metadata we started with...
			}
		}
		return beanMethods;
	}


	/**
	 * Process the given <code>@PropertySource</code> annotation metadata.
	 * @param propertySource metadata for the <code>@PropertySource</code> annotation found
	 * @throws IOException if loading a property source failed
	 */
	private void processPropertySource(AnnotationAttributes propertySource) throws IOException { // 处理@PropertySource注解
		String name = propertySource.getString("name");
		if (!StringUtils.hasLength(name)) {
			name = null;
		}
		String encoding = propertySource.getString("encoding");
		if (!StringUtils.hasLength(encoding)) {
			encoding = null;
		}
		String[] locations = propertySource.getStringArray("value"); // 获取@PropertySource注解的value属性值，即包路径
		Assert.isTrue(locations.length > 0, "At least one @PropertySource(value) location is required");
		boolean ignoreResourceNotFound = propertySource.getBoolean("ignoreResourceNotFound");

		Class<? extends PropertySourceFactory> factoryClass = propertySource.getClass("factory");
		PropertySourceFactory factory = (factoryClass == PropertySourceFactory.class ?
				DEFAULT_PROPERTY_SOURCE_FACTORY : BeanUtils.instantiateClass(factoryClass));

		for (String location : locations) { // 遍历包路径
			try {
				String resolvedLocation = this.environment.resolveRequiredPlaceholders(location); // 解析占位符
				Resource resource = this.resourceLoader.getResource(resolvedLocation); // 以流的方式加载配置文件并封装成Resoure对象
				addPropertySource(factory.createPropertySource(name, new EncodedResource(resource, encoding))); // 加载Resource中的配置属性封装到PropertySource对象中，并添加到Environment对象中
			}
			catch (IllegalArgumentException | FileNotFoundException | UnknownHostException ex) {
				// Placeholders not resolvable or resource not found when trying to open it
				if (ignoreResourceNotFound) {
					if (logger.isInfoEnabled()) {
						logger.info("Properties location [" + location + "] not resolvable: " + ex.getMessage());
					}
				}
				else {
					throw ex;
				}
			}
		}
	}

	private void addPropertySource(PropertySource<?> propertySource) { // 添加PropertySource到Environment对象中
		String name = propertySource.getName(); // 获取PropertySource的name属性值（如果没指定名称，则使用默认生成的name）
		MutablePropertySources propertySources = ((ConfigurableEnvironment) this.environment).getPropertySources(); // 获取Environment对象中的MutablePropertySources

		if (this.propertySourceNames.contains(name)) { // 如果存在同名的配置文件，则进行合并
			// We've already added a version, we need to extend it
			PropertySource<?> existing = propertySources.get(name);
			if (existing != null) {
				PropertySource<?> newSource = (propertySource instanceof ResourcePropertySource ?
						((ResourcePropertySource) propertySource).withResourceName() : propertySource);
				if (existing instanceof CompositePropertySource) { // 第二次直接追加
					((CompositePropertySource) existing).addFirstPropertySource(newSource);
				}
				else {
					if (existing instanceof ResourcePropertySource) { // 首次进行类型转换
						existing = ((ResourcePropertySource) existing).withResourceName(); // 原有的PropertySource
					}
					CompositePropertySource composite = new CompositePropertySource(name); // 创建支持合并PropertySource对象的CompositePropertySource
					composite.addPropertySource(newSource); // 添加现在的PropertySource（新的在前、旧的在后）
					composite.addPropertySource(existing); // 添加原有的PropertySource
					propertySources.replace(name, composite); // 替换PropertySource类型
				}
				return;
			}
		}
		// 不存在同名的配置文件时会走下面逻辑
		if (this.propertySourceNames.isEmpty()) {
			propertySources.addLast(propertySource); // 当已添加的PropertySource为空时，将PropertySource添加到Environment中的MutablePropertySources集合尾部
		}
		else {
			String firstProcessed = this.propertySourceNames.get(this.propertySourceNames.size() - 1);
			propertySources.addBefore(firstProcessed, propertySource);
		}
		this.propertySourceNames.add(name);
	}


	/**
	 * Returns {@code @Import} class, considering all meta-annotations.
	 */
	private Set<SourceClass> getImports(SourceClass sourceClass) throws IOException { // 获取@Import注解导入的类并封装成SourceClass
		Set<SourceClass> imports = new LinkedHashSet<>();
		Set<SourceClass> visited = new LinkedHashSet<>();
		collectImports(sourceClass, imports, visited); // 获取@Import注解导入的类并添加到集合中
		return imports;
	}

	/**
	 * Recursively collect all declared {@code @Import} values. Unlike most
	 * meta-annotations it is valid to have several {@code @Import}s declared with
	 * different values; the usual process of returning values from the first
	 * meta-annotation on a class is not sufficient.
	 * <p>For example, it is common for a {@code @Configuration} class to declare direct
	 * {@code @Import}s in addition to meta-imports originating from an {@code @Enable}
	 * annotation.
	 * @param sourceClass the class to search
	 * @param imports the imports collected so far
	 * @param visited used to track visited classes to prevent infinite recursion
	 * @throws IOException if there is any problem reading metadata from the named class
	 */
	private void collectImports(SourceClass sourceClass, Set<SourceClass> imports, Set<SourceClass> visited) // 获取@Import注解导入的类并添加到集合中
			throws IOException {

		if (visited.add(sourceClass)) {
			for (SourceClass annotation : sourceClass.getAnnotations()) {
				String annName = annotation.getMetadata().getClassName();
				if (!annName.equals(Import.class.getName())) {
					collectImports(annotation, imports, visited);
				}
			}
			imports.addAll(sourceClass.getAnnotationAttributes(Import.class.getName(), "value")); // 获取@Import注解中的value属性值，并添加到集合中
		}
	}

	private void processImports(ConfigurationClass configClass, SourceClass currentSourceClass, // 处理配置类上有@Import的注解
			Collection<SourceClass> importCandidates, boolean checkForCircularImports) {

		if (importCandidates.isEmpty()) { // 如果Import类为空，则直接返回不进行处理
			return;
		}

		if (checkForCircularImports && isChainedImportOnStack(configClass)) {
			this.problemReporter.error(new CircularImportProblem(configClass, this.importStack));
		}
		else {
			this.importStack.push(configClass);
			try {
				for (SourceClass candidate : importCandidates) { // 遍历@Import导入的类
					if (candidate.isAssignable(ImportSelector.class)) { // 如果Import类实现了ImportSelector接口
						// Candidate class is an ImportSelector -> delegate to it to determine imports
						Class<?> candidateClass = candidate.loadClass();
						ImportSelector selector = ParserStrategyUtils.instantiateClass(candidateClass, ImportSelector.class, // 通过反射进行实例化ImportSelector
								this.environment, this.resourceLoader, this.registry);
						if (selector instanceof DeferredImportSelector) { // ImportSelector实现类是DeferredImportSelector类型的处理逻辑
							this.deferredImportSelectorHandler.handle(configClass, (DeferredImportSelector) selector); // 添加DeferredImportSelector类（处理的时机在ConfigurationClassParser#parse(...)方法的结束前）
						}
						else { // ImportSelector实现类不是DeferredImportSelector类型的处理逻辑
							String[] importClassNames = selector.selectImports(currentSourceClass.getMetadata()); // 调用ImportSelector接口的selectImports方法，获取所有需要import到spring容器的beanName
							Collection<SourceClass> importSourceClasses = asSourceClasses(importClassNames); // 将导入的类名封装成SourceClass对象
							processImports(configClass, currentSourceClass, importSourceClasses, false); // 递归调用ConfigurationClassParser#processImports(...)方法
						}
					}
					else if (candidate.isAssignable(ImportBeanDefinitionRegistrar.class)) { // 如果Import类实现了ImportBeanDefinitionRegistrar接口
						// Candidate class is an ImportBeanDefinitionRegistrar ->
						// delegate to it to register additional bean definitions
						Class<?> candidateClass = candidate.loadClass();
						ImportBeanDefinitionRegistrar registrar =
								ParserStrategyUtils.instantiateClass(candidateClass, ImportBeanDefinitionRegistrar.class, // 通过反射进行实例化ImportBeanDefinitionRegistrar
										this.environment, this.resourceLoader, this.registry);
						configClass.addImportBeanDefinitionRegistrar(registrar, currentSourceClass.getMetadata()); // 添加ImportBeanDefinitionRegistrar与AnnotationMetadata的映射关系（并没有调用接口的registerBeanDefinitions方法）
					}
					else { // 导入的是一个普通的类
						// Candidate class not an ImportSelector or ImportBeanDefinitionRegistrar ->
						// process it as an @Configuration class
						this.importStack.registerImport( // 添加Import映射关系
								currentSourceClass.getMetadata(), candidate.getMetadata().getClassName());
						processConfigurationClass(candidate.asConfigClass(configClass)); // 递归调用ConfigurationClassParser#processConfigurationClass(...)方法
					}
				}
			}
			catch (BeanDefinitionStoreException ex) {
				throw ex;
			}
			catch (Throwable ex) {
				throw new BeanDefinitionStoreException(
						"Failed to process import candidates for configuration class [" +
						configClass.getMetadata().getClassName() + "]", ex);
			}
			finally {
				this.importStack.pop();
			}
		}
	}

	private boolean isChainedImportOnStack(ConfigurationClass configClass) {
		if (this.importStack.contains(configClass)) {
			String configClassName = configClass.getMetadata().getClassName();
			AnnotationMetadata importingClass = this.importStack.getImportingClassFor(configClassName);
			while (importingClass != null) {
				if (configClassName.equals(importingClass.getClassName())) {
					return true;
				}
				importingClass = this.importStack.getImportingClassFor(importingClass.getClassName());
			}
		}
		return false;
	}

	ImportRegistry getImportRegistry() {
		return this.importStack;
	}


	/**
	 * Factory method to obtain a {@link SourceClass} from a {@link ConfigurationClass}.
	 */
	private SourceClass asSourceClass(ConfigurationClass configurationClass) throws IOException { // 将ConfigurationClass封装到SourceClass中
		AnnotationMetadata metadata = configurationClass.getMetadata();
		if (metadata instanceof StandardAnnotationMetadata) {
			return asSourceClass(((StandardAnnotationMetadata) metadata).getIntrospectedClass());
		}
		return asSourceClass(metadata.getClassName());
	}

	/**
	 * Factory method to obtain a {@link SourceClass} from a {@link Class}.
	 */
	SourceClass asSourceClass(@Nullable Class<?> classType) throws IOException {
		if (classType == null || classType.getName().startsWith("java.lang.annotation.")) {
			return this.objectSourceClass;
		}
		try {
			// Sanity test that we can reflectively read annotations,
			// including Class attributes; if not -> fall back to ASM
			for (Annotation ann : classType.getDeclaredAnnotations()) {
				AnnotationUtils.validateAnnotation(ann);
			}
			return new SourceClass(classType);
		}
		catch (Throwable ex) {
			// Enforce ASM via class name resolution
			return asSourceClass(classType.getName());
		}
	}

	/**
	 * Factory method to obtain {@link SourceClass SourceClasss} from class names.
	 */
	private Collection<SourceClass> asSourceClasses(String... classNames) throws IOException {
		List<SourceClass> annotatedClasses = new ArrayList<>(classNames.length);
		for (String className : classNames) {
			annotatedClasses.add(asSourceClass(className));
		}
		return annotatedClasses;
	}

	/**
	 * Factory method to obtain a {@link SourceClass} from a class name.
	 */
	SourceClass asSourceClass(@Nullable String className) throws IOException {
		if (className == null || className.startsWith("java.lang.annotation.")) {
			return this.objectSourceClass;
		}
		if (className.startsWith("java")) {
			// Never use ASM for core java types
			try {
				return new SourceClass(ClassUtils.forName(className,
						this.resourceLoader.getClassLoader()));
			}
			catch (ClassNotFoundException ex) {
				throw new NestedIOException(
						"Failed to load class [" + className + "]", ex);
			}
		}
		return new SourceClass(
				this.metadataReaderFactory.getMetadataReader(className));
	}


	@SuppressWarnings("serial")
	private static class ImportStack extends ArrayDeque<ConfigurationClass> implements ImportRegistry {

		private final MultiValueMap<String, AnnotationMetadata> imports = new LinkedMultiValueMap<>();

		public void registerImport(AnnotationMetadata importingClass, String importedClass) { // 添加Import映射关系
			this.imports.add(importedClass, importingClass);
		}

		@Override
		@Nullable
		public AnnotationMetadata getImportingClassFor(String importedClass) {
			return CollectionUtils.lastElement(this.imports.get(importedClass));
		}

		@Override
		public void removeImportingClass(String importingClass) {
			for (List<AnnotationMetadata> list : this.imports.values()) {
				for (Iterator<AnnotationMetadata> iterator = list.iterator(); iterator.hasNext();) {
					if (iterator.next().getClassName().equals(importingClass)) {
						iterator.remove();
						break;
					}
				}
			}
		}

		/**
		 * Given a stack containing (in order)
		 * <ul>
		 * <li>com.acme.Foo</li>
		 * <li>com.acme.Bar</li>
		 * <li>com.acme.Baz</li>
		 * </ul>
		 * return "[Foo->Bar->Baz]".
		 */
		@Override
		public String toString() {
			StringJoiner joiner = new StringJoiner("->", "[", "]");
			for (ConfigurationClass configurationClass : this) {
				joiner.add(configurationClass.getSimpleName());
			}
			return joiner.toString();
		}
	}


	private class DeferredImportSelectorHandler { // DeferredImportSelector处理器

		@Nullable
		private List<DeferredImportSelectorHolder> deferredImportSelectors = new ArrayList<>();

		/**
		 * Handle the specified {@link DeferredImportSelector}. If deferred import
		 * selectors are being collected, this registers this instance to the list. If
		 * they are being processed, the {@link DeferredImportSelector} is also processed
		 * immediately according to its {@link DeferredImportSelector.Group}.
		 * @param configClass the source configuration class
		 * @param importSelector the selector to handle
		 */
		public void handle(ConfigurationClass configClass, DeferredImportSelector importSelector) { // 添加DeferredImportSelector类
			DeferredImportSelectorHolder holder = new DeferredImportSelectorHolder( // 将传入的参数封装到DeferredImportSelectorHolder对象中
					configClass, importSelector);
			if (this.deferredImportSelectors == null) { // 为null的场景比较特殊，一般情况下不会出现
				DeferredImportSelectorGroupingHandler handler = new DeferredImportSelectorGroupingHandler(); // 创建DeferredImportSelectorGroupingHandler
				handler.register(holder); // 注册
				handler.processGroupImports(); // 处理
			}
			else {
				this.deferredImportSelectors.add(holder); // 添加DeferredImportSelectorHolder到集合中
			}
		}

		public void process() { // 处理DeferredImportSelector列表
			List<DeferredImportSelectorHolder> deferredImports = this.deferredImportSelectors; // 将列表赋值给局部变量
			this.deferredImportSelectors = null; // 移除列表的引用
			try {
				if (deferredImports != null) {
					DeferredImportSelectorGroupingHandler handler = new DeferredImportSelectorGroupingHandler(); // 创建DeferredImportSelectorGroupingHandler
					deferredImports.sort(DEFERRED_IMPORT_COMPARATOR); // 排序
					deferredImports.forEach(handler::register); // 循环注册
					handler.processGroupImports(); // 处理
				}
			}
			finally {
				this.deferredImportSelectors = new ArrayList<>(); // 将列表指向新的集合
			}
		}

	}


	private class DeferredImportSelectorGroupingHandler { // DeferredImportSelectorGroupingHandler

		private final Map<Object, DeferredImportSelectorGrouping> groupings = new LinkedHashMap<>();

		private final Map<AnnotationMetadata, ConfigurationClass> configurationClasses = new HashMap<>();

		public void register(DeferredImportSelectorHolder deferredImport) { // 注册
			Class<? extends Group> group = deferredImport.getImportSelector() // 调用DeferredImportSelector#getImportGroup()方法获取DeferredImportSelector.Group的实现类
					.getImportGroup();
			DeferredImportSelectorGrouping grouping = this.groupings.computeIfAbsent( // 根据DeferredImportSelector.Group获取或创建对应的DeferredImportSelectorGrouping
					(group != null ? group : deferredImport),
					key -> new DeferredImportSelectorGrouping(createGroup(group))); // 通过反射创建DeferredImportSelector.Group的实现类（如果没有使用Spring默认的Group），并添加到DeferredImportSelectorGrouping中
			grouping.add(deferredImport); // 添加DeferredImportSelectorHolder
			this.configurationClasses.put(deferredImport.getConfigurationClass().getMetadata(),
					deferredImport.getConfigurationClass());
		}

		public void processGroupImports() { // 处理
			for (DeferredImportSelectorGrouping grouping : this.groupings.values()) { // 遍历DeferredImportSelectorGrouping
				grouping.getImports().forEach(entry -> { // 调用DeferredImportSelector.Group类方法，并遍历返回列表
					ConfigurationClass configurationClass = this.configurationClasses.get(
							entry.getMetadata());
					try {
						processImports(configurationClass, asSourceClass(configurationClass), // 递归调用递归调用ConfigurationClassParser#processImports(...)方法
								asSourceClasses(entry.getImportClassName()), false);
					}
					catch (BeanDefinitionStoreException ex) {
						throw ex;
					}
					catch (Throwable ex) {
						throw new BeanDefinitionStoreException(
								"Failed to process import candidates for configuration class [" +
										configurationClass.getMetadata().getClassName() + "]", ex);
					}
				});
			}
		}

		private Group createGroup(@Nullable Class<? extends Group> type) {
			Class<? extends Group> effectiveType = (type != null ? type
					: DefaultDeferredImportSelectorGroup.class);
			Group group = ParserStrategyUtils.instantiateClass(effectiveType, Group.class,
					ConfigurationClassParser.this.environment,
					ConfigurationClassParser.this.resourceLoader,
					ConfigurationClassParser.this.registry);
			return group;
		}

	}


	private static class DeferredImportSelectorHolder {

		private final ConfigurationClass configurationClass;

		private final DeferredImportSelector importSelector;

		public DeferredImportSelectorHolder(ConfigurationClass configClass, DeferredImportSelector selector) { // 初始化DeferredImportSelectorHolder
			this.configurationClass = configClass;
			this.importSelector = selector;
		}

		public ConfigurationClass getConfigurationClass() {
			return this.configurationClass;
		}

		public DeferredImportSelector getImportSelector() {
			return this.importSelector;
		}
	}


	private static class DeferredImportSelectorGrouping {

		private final DeferredImportSelector.Group group; // 维护DeferredImportSelector.Group实现类

		private final List<DeferredImportSelectorHolder> deferredImports = new ArrayList<>();

		DeferredImportSelectorGrouping(Group group) { // 初始化DeferredImportSelectorGrouping
			this.group = group;
		}

		public void add(DeferredImportSelectorHolder deferredImport) { // 添加DeferredImportSelectorHolder
			this.deferredImports.add(deferredImport);
		}

		/**
		 * Return the imports defined by the group.
		 * @return each import with its associated configuration class
		 */
		public Iterable<Group.Entry> getImports() { // 调用DeferredImportSelector.Group类方法
			for (DeferredImportSelectorHolder deferredImport : this.deferredImports) {
				this.group.process(deferredImport.getConfigurationClass().getMetadata(), // 调用DeferredImportSelector.Group#process方法（如果没有指定Group，则会用Spring默认的Group）
						deferredImport.getImportSelector());
			}
			return this.group.selectImports(); // 调用DeferredImportSelector.Group#selectImports方法
		}
	}


	private static class DefaultDeferredImportSelectorGroup implements Group {

		private final List<Entry> imports = new ArrayList<>();

		@Override
		public void process(AnnotationMetadata metadata, DeferredImportSelector selector) {
			for (String importClassName : selector.selectImports(metadata)) {
				this.imports.add(new Entry(metadata, importClassName));
			}
		}

		@Override
		public Iterable<Entry> selectImports() {
			return this.imports;
		}
	}


	/**
	 * Simple wrapper that allows annotated source classes to be dealt with
	 * in a uniform manner, regardless of how they are loaded.
	 */
	private class SourceClass implements Ordered {

		private final Object source;  // Class or MetadataReader

		private final AnnotationMetadata metadata;

		public SourceClass(Object source) {
			this.source = source;
			if (source instanceof Class) {
				this.metadata = AnnotationMetadata.introspect((Class<?>) source);
			}
			else {
				this.metadata = ((MetadataReader) source).getAnnotationMetadata();
			}
		}

		public final AnnotationMetadata getMetadata() {
			return this.metadata;
		}

		@Override
		public int getOrder() {
			Integer order = ConfigurationClassUtils.getOrder(this.metadata);
			return (order != null ? order : Ordered.LOWEST_PRECEDENCE);
		}

		public Class<?> loadClass() throws ClassNotFoundException {
			if (this.source instanceof Class) {
				return (Class<?>) this.source;
			}
			String className = ((MetadataReader) this.source).getClassMetadata().getClassName();
			return ClassUtils.forName(className, resourceLoader.getClassLoader());
		}

		public boolean isAssignable(Class<?> clazz) throws IOException {
			if (this.source instanceof Class) {
				return clazz.isAssignableFrom((Class<?>) this.source);
			}
			return new AssignableTypeFilter(clazz).match((MetadataReader) this.source, metadataReaderFactory);
		}

		public ConfigurationClass asConfigClass(ConfigurationClass importedBy) {
			if (this.source instanceof Class) {
				return new ConfigurationClass((Class<?>) this.source, importedBy);
			}
			return new ConfigurationClass((MetadataReader) this.source, importedBy);
		}

		public Collection<SourceClass> getMemberClasses() throws IOException { // 获取当前SourceClass的内部类集合
			Object sourceToProcess = this.source;
			if (sourceToProcess instanceof Class) {
				Class<?> sourceClass = (Class<?>) sourceToProcess;
				try {
					Class<?>[] declaredClasses = sourceClass.getDeclaredClasses(); // 获取内部类
					List<SourceClass> members = new ArrayList<>(declaredClasses.length);
					for (Class<?> declaredClass : declaredClasses) {
						members.add(asSourceClass(declaredClass));
					}
					return members;
				}
				catch (NoClassDefFoundError err) {
					// getDeclaredClasses() failed because of non-resolvable dependencies
					// -> fall back to ASM below
					sourceToProcess = metadataReaderFactory.getMetadataReader(sourceClass.getName());
				}
			}

			// ASM-based resolution - safe for non-resolvable classes as well
			MetadataReader sourceReader = (MetadataReader) sourceToProcess;
			String[] memberClassNames = sourceReader.getClassMetadata().getMemberClassNames();
			List<SourceClass> members = new ArrayList<>(memberClassNames.length);
			for (String memberClassName : memberClassNames) {
				try {
					members.add(asSourceClass(memberClassName));
				}
				catch (IOException ex) {
					// Let's skip it if it's not resolvable - we're just looking for candidates
					if (logger.isDebugEnabled()) {
						logger.debug("Failed to resolve member class [" + memberClassName +
								"] - not considering it as a configuration class candidate");
					}
				}
			}
			return members;
		}

		public SourceClass getSuperClass() throws IOException {
			if (this.source instanceof Class) {
				return asSourceClass(((Class<?>) this.source).getSuperclass());
			}
			return asSourceClass(((MetadataReader) this.source).getClassMetadata().getSuperClassName());
		}

		public Set<SourceClass> getInterfaces() throws IOException {
			Set<SourceClass> result = new LinkedHashSet<>();
			if (this.source instanceof Class) {
				Class<?> sourceClass = (Class<?>) this.source;
				for (Class<?> ifcClass : sourceClass.getInterfaces()) {
					result.add(asSourceClass(ifcClass));
				}
			}
			else {
				for (String className : this.metadata.getInterfaceNames()) {
					result.add(asSourceClass(className));
				}
			}
			return result;
		}

		public Set<SourceClass> getAnnotations() {
			Set<SourceClass> result = new LinkedHashSet<>();
			if (this.source instanceof Class) {
				Class<?> sourceClass = (Class<?>) this.source;
				for (Annotation ann : sourceClass.getDeclaredAnnotations()) {
					Class<?> annType = ann.annotationType();
					if (!annType.getName().startsWith("java")) {
						try {
							result.add(asSourceClass(annType));
						}
						catch (Throwable ex) {
							// An annotation not present on the classpath is being ignored
							// by the JVM's class loading -> ignore here as well.
						}
					}
				}
			}
			else {
				for (String className : this.metadata.getAnnotationTypes()) {
					if (!className.startsWith("java")) {
						try {
							result.add(getRelated(className));
						}
						catch (Throwable ex) {
							// An annotation not present on the classpath is being ignored
							// by the JVM's class loading -> ignore here as well.
						}
					}
				}
			}
			return result;
		}

		public Collection<SourceClass> getAnnotationAttributes(String annType, String attribute) throws IOException {
			Map<String, Object> annotationAttributes = this.metadata.getAnnotationAttributes(annType, true);
			if (annotationAttributes == null || !annotationAttributes.containsKey(attribute)) {
				return Collections.emptySet();
			}
			String[] classNames = (String[]) annotationAttributes.get(attribute);
			Set<SourceClass> result = new LinkedHashSet<>();
			for (String className : classNames) {
				result.add(getRelated(className));
			}
			return result;
		}

		private SourceClass getRelated(String className) throws IOException {
			if (this.source instanceof Class) {
				try {
					Class<?> clazz = ClassUtils.forName(className, ((Class<?>) this.source).getClassLoader());
					return asSourceClass(clazz);
				}
				catch (ClassNotFoundException ex) {
					// Ignore -> fall back to ASM next, except for core java types.
					if (className.startsWith("java")) {
						throw new NestedIOException("Failed to load class [" + className + "]", ex);
					}
					return new SourceClass(metadataReaderFactory.getMetadataReader(className));
				}
			}
			return asSourceClass(className);
		}

		@Override
		public boolean equals(@Nullable Object other) {
			return (this == other || (other instanceof SourceClass &&
					this.metadata.getClassName().equals(((SourceClass) other).metadata.getClassName())));
		}

		@Override
		public int hashCode() {
			return this.metadata.getClassName().hashCode();
		}

		@Override
		public String toString() {
			return this.metadata.getClassName();
		}
	}


	/**
	 * {@link Problem} registered upon detection of a circular {@link Import}.
	 */
	private static class CircularImportProblem extends Problem {

		public CircularImportProblem(ConfigurationClass attemptedImport, Deque<ConfigurationClass> importStack) {
			super(String.format("A circular @Import has been detected: " +
					"Illegal attempt by @Configuration class '%s' to import class '%s' as '%s' is " +
					"already present in the current import stack %s", importStack.element().getSimpleName(),
					attemptedImport.getSimpleName(), attemptedImport.getSimpleName(), importStack),
					new Location(importStack.element().getResource(), attemptedImport.getMetadata()));
		}
	}

}
