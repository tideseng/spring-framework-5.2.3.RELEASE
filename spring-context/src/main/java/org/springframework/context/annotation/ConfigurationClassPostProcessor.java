/*
 * Copyright 2002-2020 the original author or authors.
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.aop.framework.autoproxy.AutoProxyUtils;
import org.springframework.beans.PropertyValues;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessorAdapter;
import org.springframework.beans.factory.config.SingletonBeanRegistry;
import org.springframework.beans.factory.parsing.FailFastProblemReporter;
import org.springframework.beans.factory.parsing.PassThroughSourceExtractor;
import org.springframework.beans.factory.parsing.ProblemReporter;
import org.springframework.beans.factory.parsing.SourceExtractor;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.ConfigurationClassEnhancer.EnhancedConfiguration;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.MethodMetadata;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;

/**
 * {@link BeanFactoryPostProcessor} used for bootstrapping processing of
 * {@link Configuration @Configuration} classes.
 *
 * <p>Registered by default when using {@code <context:annotation-config/>} or
 * {@code <context:component-scan/>}. Otherwise, may be declared manually as
 * with any other BeanFactoryPostProcessor.
 *
 * <p>This post processor is priority-ordered as it is important that any
 * {@link Bean} methods declared in {@code @Configuration} classes have
 * their corresponding bean definitions registered before any other
 * {@link BeanFactoryPostProcessor} executes.
 *
 * @author Chris Beams
 * @author Juergen Hoeller
 * @author Phillip Webb
 * @since 3.0
 */
public class ConfigurationClassPostProcessor implements BeanDefinitionRegistryPostProcessor, // 实现了BeanDefinitionRegistryPostProcessor接口，会重写相关方法解析配置类，处理@Component、@PropertySource、@ComponentScan、@Import、@ImportResource、@Bean注解
		PriorityOrdered, ResourceLoaderAware, BeanClassLoaderAware, EnvironmentAware {

	/**
	 * A {@code BeanNameGenerator} using fully qualified class names as default bean names.
	 * <p>This default for configuration-level import purposes may be overridden through
	 * {@link #setBeanNameGenerator}. Note that the default for component scanning purposes
	 * is a plain {@link AnnotationBeanNameGenerator#INSTANCE}, unless overridden through
	 * {@link #setBeanNameGenerator} with a unified user-level bean name generator.
	 * @since 5.2
	 * @see #setBeanNameGenerator
	 */
	public static final AnnotationBeanNameGenerator IMPORT_BEAN_NAME_GENERATOR = // 全限定类名生成器
			new FullyQualifiedAnnotationBeanNameGenerator();

	private static final String IMPORT_REGISTRY_BEAN_NAME =
			ConfigurationClassPostProcessor.class.getName() + ".importRegistry";


	private final Log logger = LogFactory.getLog(getClass());

	private SourceExtractor sourceExtractor = new PassThroughSourceExtractor();

	private ProblemReporter problemReporter = new FailFastProblemReporter();

	@Nullable
	private Environment environment; // 默认会由EnvironmentAware接口注入

	private ResourceLoader resourceLoader = new DefaultResourceLoader(); // 默认会由ResourceLoaderAware接口注入

	@Nullable
	private ClassLoader beanClassLoader = ClassUtils.getDefaultClassLoader(); // 默认会由BeanClassLoaderAware接口注入

	private MetadataReaderFactory metadataReaderFactory = new CachingMetadataReaderFactory();

	private boolean setMetadataReaderFactoryCalled = false;

	private final Set<Integer> registriesPostProcessed = new HashSet<>();

	private final Set<Integer> factoriesPostProcessed = new HashSet<>();

	@Nullable
	private ConfigurationClassBeanDefinitionReader reader;

	private boolean localBeanNameGeneratorSet = false; // 是否指定了beanName生成器

	/* Using short class names as default bean names by default. */
	private BeanNameGenerator componentScanBeanNameGenerator = AnnotationBeanNameGenerator.INSTANCE; // 默认的短类名生成器

	/* Using fully qualified class names as default bean names by default. */
	private BeanNameGenerator importBeanNameGenerator = IMPORT_BEAN_NAME_GENERATOR; // 默认的全限定类名生成器


	@Override
	public int getOrder() { // PriorityOrdered接口方法实现，最低级别
		return Ordered.LOWEST_PRECEDENCE;  // within PriorityOrdered
	}

	/**
	 * Set the {@link SourceExtractor} to use for generated bean definitions
	 * that correspond to {@link Bean} factory methods.
	 */
	public void setSourceExtractor(@Nullable SourceExtractor sourceExtractor) {
		this.sourceExtractor = (sourceExtractor != null ? sourceExtractor : new PassThroughSourceExtractor());
	}

	/**
	 * Set the {@link ProblemReporter} to use.
	 * <p>Used to register any problems detected with {@link Configuration} or {@link Bean}
	 * declarations. For instance, an @Bean method marked as {@code final} is illegal
	 * and would be reported as a problem. Defaults to {@link FailFastProblemReporter}.
	 */
	public void setProblemReporter(@Nullable ProblemReporter problemReporter) {
		this.problemReporter = (problemReporter != null ? problemReporter : new FailFastProblemReporter());
	}

	/**
	 * Set the {@link MetadataReaderFactory} to use.
	 * <p>Default is a {@link CachingMetadataReaderFactory} for the specified
	 * {@linkplain #setBeanClassLoader bean class loader}.
	 */
	public void setMetadataReaderFactory(MetadataReaderFactory metadataReaderFactory) {
		Assert.notNull(metadataReaderFactory, "MetadataReaderFactory must not be null");
		this.metadataReaderFactory = metadataReaderFactory;
		this.setMetadataReaderFactoryCalled = true;
	}

	/**
	 * Set the {@link BeanNameGenerator} to be used when triggering component scanning
	 * from {@link Configuration} classes and when registering {@link Import}'ed
	 * configuration classes. The default is a standard {@link AnnotationBeanNameGenerator}
	 * for scanned components (compatible with the default in {@link ClassPathBeanDefinitionScanner})
	 * and a variant thereof for imported configuration classes (using unique fully-qualified
	 * class names instead of standard component overriding).
	 * <p>Note that this strategy does <em>not</em> apply to {@link Bean} methods.
	 * <p>This setter is typically only appropriate when configuring the post-processor as a
	 * standalone bean definition in XML, e.g. not using the dedicated {@code AnnotationConfig*}
	 * application contexts or the {@code <context:annotation-config>} element. Any bean name
	 * generator specified against the application context will take precedence over any set here.
	 * @since 3.1.1
	 * @see AnnotationConfigApplicationContext#setBeanNameGenerator(BeanNameGenerator)
	 * @see AnnotationConfigUtils#CONFIGURATION_BEAN_NAME_GENERATOR
	 */
	public void setBeanNameGenerator(BeanNameGenerator beanNameGenerator) { // 指定beanName生成器
		Assert.notNull(beanNameGenerator, "BeanNameGenerator must not be null");
		this.localBeanNameGeneratorSet = true; // 将localBeanNameGeneratorSet变量设置为true
		this.componentScanBeanNameGenerator = beanNameGenerator;
		this.importBeanNameGenerator = beanNameGenerator;
	}

	@Override
	public void setEnvironment(Environment environment) { // EnvironmentAware接口调用
		Assert.notNull(environment, "Environment must not be null");
		this.environment = environment;
	}

	@Override
	public void setResourceLoader(ResourceLoader resourceLoader) { // ResourceLoaderAware接口调用
		Assert.notNull(resourceLoader, "ResourceLoader must not be null");
		this.resourceLoader = resourceLoader;
		if (!this.setMetadataReaderFactoryCalled) {
			this.metadataReaderFactory = new CachingMetadataReaderFactory(resourceLoader);
		}
	}

	@Override
	public void setBeanClassLoader(ClassLoader beanClassLoader) { // BeanClassLoaderAware接口调用
		this.beanClassLoader = beanClassLoader;
		if (!this.setMetadataReaderFactoryCalled) {
			this.metadataReaderFactory = new CachingMetadataReaderFactory(beanClassLoader);
		}
	}


	/**
	 * Derive further bean definitions from the configuration classes in the registry.
	 */
	@Override
	public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) { // BeanDefinitionRegistryPostProcessor实现类的postProcessBeanDefinitionRegistry方法
		int registryId = System.identityHashCode(registry);
		if (this.registriesPostProcessed.contains(registryId)) {
			throw new IllegalStateException(
					"postProcessBeanDefinitionRegistry already called on this post-processor against " + registry);
		}
		if (this.factoriesPostProcessed.contains(registryId)) {
			throw new IllegalStateException(
					"postProcessBeanFactory already called on this post-processor against " + registry);
		}
		this.registriesPostProcessed.add(registryId);

		processConfigBeanDefinitions(registry); // 解析配置类，解析BeanDefinitionRegistry注册表中的配置类并生成进一步的BeanDefinition
	}

	/**
	 * Prepare the Configuration classes for servicing bean requests at runtime
	 * by replacing them with CGLIB-enhanced subclasses.
	 */
	@Override
	public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) { // BeanFactoryPostProcessor实现类的postProcessBeanFactory方法
		int factoryId = System.identityHashCode(beanFactory); // 默认情况下BeanFacotry就是BeanDefinitionRegistry，所以获取到的ID相同
		if (this.factoriesPostProcessed.contains(factoryId)) {
			throw new IllegalStateException(
					"postProcessBeanFactory already called on this post-processor against " + beanFactory);
		}
		this.factoriesPostProcessed.add(factoryId);
		if (!this.registriesPostProcessed.contains(factoryId)) { // 默认情况为false
			// BeanDefinitionRegistryPostProcessor hook apparently not supported...
			// Simply call processConfigurationClasses lazily at this point then.
			processConfigBeanDefinitions((BeanDefinitionRegistry) beanFactory); // 解析配置类（默认情况下该类已经解析，不会调用再此解析）
		}

		enhanceConfigurationClasses(beanFactory); // 对@Configuration完全匹配配置类生成代理类
		beanFactory.addBeanPostProcessor(new ImportAwareBeanPostProcessor(beanFactory)); // 3.实例化实现了BeanPostProcessor接口的ImportAwareBeanPostProcessor，并添加到BeanFactory容器中
	}

	/**
	 * Build and validate a configuration model based on the registry of
	 * {@link Configuration} classes.
	 */
	public void processConfigBeanDefinitions(BeanDefinitionRegistry registry) { // 解析配置类，解析BeanDefinitionRegistry注册表中的配置类并生成进一步的BeanDefinition
		List<BeanDefinitionHolder> configCandidates = new ArrayList<>(); // 候选配置类的BeanDefinitionHolder集合容器
		String[] candidateNames = registry.getBeanDefinitionNames(); // 获取解析前的所有beanName

		for (String beanName : candidateNames) { // 遍历所有beanName
			BeanDefinition beanDef = registry.getBeanDefinition(beanName); // 根据beanName获取BeanDefinition
			if (beanDef.getAttribute(ConfigurationClassUtils.CONFIGURATION_CLASS_ATTRIBUTE) != null) { // 标识是否已处理，已处理则不再处理
				if (logger.isDebugEnabled()) {
					logger.debug("Bean definition has already been processed as a configuration class: " + beanDef);
				}
			}
			else if (ConfigurationClassUtils.checkConfigurationClassCandidate(beanDef, this.metadataReaderFactory)) { // 当未处理时，判断BeanDefinition是否为候选配置类，是则标记已处理并先放入到候选容器中
				configCandidates.add(new BeanDefinitionHolder(beanDef, beanName)); // 将BeanDefinition包装到BeanDefinitionHolder，并添加到候选容器中
			}
		}

		// Return immediately if no @Configuration classes were found
		if (configCandidates.isEmpty()) { // 如果候选容器为空，则直接返回不进行任务处理
			return;
		}

		// Sort by previously determined @Order value, if applicable
		configCandidates.sort((bd1, bd2) -> { // 对候选容器中的BeanDefinition根据@Order注解进行排序
			int i1 = ConfigurationClassUtils.getOrder(bd1.getBeanDefinition()); // 从BeanDefinition中获取order的attribute属性值
			int i2 = ConfigurationClassUtils.getOrder(bd2.getBeanDefinition()); // 从BeanDefinition中获取order的attribute属性值
			return Integer.compare(i1, i2);
		});

		// Detect any custom bean name generation strategy supplied through the enclosing application context
		SingletonBeanRegistry sbr = null;
		if (registry instanceof SingletonBeanRegistry) {
			sbr = (SingletonBeanRegistry) registry;
			if (!this.localBeanNameGeneratorSet) { // 当未指定beanName生成器时，检查容器中是否注入了生成器
				BeanNameGenerator generator = (BeanNameGenerator) sbr.getSingleton(
						AnnotationConfigUtils.CONFIGURATION_BEAN_NAME_GENERATOR);
				if (generator != null) { // 当容器中注入了生成器时，使用注入的生成器
					this.componentScanBeanNameGenerator = generator;
					this.importBeanNameGenerator = generator;
				}
			}
		}

		if (this.environment == null) {
			this.environment = new StandardEnvironment();
		}

		// Parse each @Configuration class
		ConfigurationClassParser parser = new ConfigurationClassParser( // 创建ConfigurationClassParser配置类解析器，用于解析配置类
				this.metadataReaderFactory, this.problemReporter, this.environment,
				this.resourceLoader, this.componentScanBeanNameGenerator, registry);

		Set<BeanDefinitionHolder> candidates = new LinkedHashSet<>(configCandidates); // 要解析的列表
		Set<ConfigurationClass> alreadyParsed = new HashSet<>(configCandidates.size()); // 已经解析的列表
		do { // do-while循环体，直到要解析的列表为空时跳出循环，所以是按批次进行解析
			parser.parse(candidates); // 解析当前批次候选配置类BeanDefinition集合
			parser.validate();

			Set<ConfigurationClass> configClasses = new LinkedHashSet<>(parser.getConfigurationClasses()); // 获取所有解析到的ConfigurationClass（不包含ImportSelector和ImportBeanDefinitionRegistrar类）
			configClasses.removeAll(alreadyParsed); // 移除已处理列表

			// Read the model and create bean definitions based on its content
			if (this.reader == null) {
				this.reader = new ConfigurationClassBeanDefinitionReader( // 创建ConfigurationClassBeanDefinitionReader
						registry, this.sourceExtractor, this.resourceLoader, this.environment,
						this.importBeanNameGenerator, parser.getImportRegistry());
			}
			this.reader.loadBeanDefinitions(configClasses); // 从ConfigurationClass中加载BeanDefinition（@Bean、@ImportResource、ImportBeanDefinitionRegistrar具体处理逻辑）
			alreadyParsed.addAll(configClasses); // 将当前批次的ConfigurationClass添加到已处理列表中

			candidates.clear(); // 清空
			if (registry.getBeanDefinitionCount() > candidateNames.length) { // 当解析后的BeanDefinition数量大于解析前的数量时
				String[] newCandidateNames = registry.getBeanDefinitionNames(); // 获取解析后的所有beanName
				Set<String> oldCandidateNames = new HashSet<>(Arrays.asList(candidateNames)); // 解析前的所有beanName
				Set<String> alreadyParsedClasses = new HashSet<>(); // 已经处理过的beanName
				for (ConfigurationClass configurationClass : alreadyParsed) {
					alreadyParsedClasses.add(configurationClass.getMetadata().getClassName());
				}
				for (String candidateName : newCandidateNames) { // 遍历解析后的所有beanName
					if (!oldCandidateNames.contains(candidateName)) { // 当是解析新增的BeanDefinition时
						BeanDefinition bd = registry.getBeanDefinition(candidateName);
						if (ConfigurationClassUtils.checkConfigurationClassCandidate(bd, this.metadataReaderFactory) &&
								!alreadyParsedClasses.contains(bd.getBeanClassName())) {
							candidates.add(new BeanDefinitionHolder(bd, candidateName)); // 当新增的BeanDefinition为候选配置类，且还没有被处理时，添加到要解析的列表中（如：通过ImportBeanDefinitionRegistrar接口手动注册的BeanDefinition）
						}
					}
				}
				candidateNames = newCandidateNames;
			}
		}
		while (!candidates.isEmpty()); // 当有新的要解析的列表时，循环解析

		// Register the ImportRegistry as a bean in order to support ImportAware @Configuration classes
		if (sbr != null && !sbr.containsSingleton(IMPORT_REGISTRY_BEAN_NAME)) {
			sbr.registerSingleton(IMPORT_REGISTRY_BEAN_NAME, parser.getImportRegistry());
		}

		if (this.metadataReaderFactory instanceof CachingMetadataReaderFactory) {
			// Clear cache in externally provided MetadataReaderFactory; this is a no-op
			// for a shared cache since it'll be cleared by the ApplicationContext.
			((CachingMetadataReaderFactory) this.metadataReaderFactory).clearCache();
		}
	}

	/**
	 * Post-processes a BeanFactory in search of Configuration class BeanDefinitions;
	 * any candidates are then enhanced by a {@link ConfigurationClassEnhancer}.
	 * Candidate status is determined by BeanDefinition attribute metadata.
	 * @see ConfigurationClassEnhancer
	 */
	public void enhanceConfigurationClasses(ConfigurableListableBeanFactory beanFactory) { // 对@Configuration完全匹配配置类生成代理类（@Bean修饰的方法被BeanMethodInterceptor拦截、setBeanFactory方法被BeanFactoryAwareMethodInterceptor拦截、其余被NoOp.INSTANCE拦截）
		Map<String, AbstractBeanDefinition> configBeanDefs = new LinkedHashMap<>(); // @Configuration完全匹配配置类容器
		for (String beanName : beanFactory.getBeanDefinitionNames()) { // 遍历所有beanName
			BeanDefinition beanDef = beanFactory.getBeanDefinition(beanName); // 获取BeanDefinition
			Object configClassAttr = beanDef.getAttribute(ConfigurationClassUtils.CONFIGURATION_CLASS_ATTRIBUTE); // 获取configurationClass属性值
			MethodMetadata methodMetadata = null;
			if (beanDef instanceof AnnotatedBeanDefinition) {
				methodMetadata = ((AnnotatedBeanDefinition) beanDef).getFactoryMethodMetadata();
			}
			if ((configClassAttr != null || methodMetadata != null) && beanDef instanceof AbstractBeanDefinition) {
				// Configuration class (full or lite) or a configuration-derived @Bean method
				// -> resolve bean class at this point...
				AbstractBeanDefinition abd = (AbstractBeanDefinition) beanDef;
				if (!abd.hasBeanClass()) { // 当BeanDefinition的beanClass不属于Class时
					try {
						abd.resolveBeanClass(this.beanClassLoader);
					}
					catch (Throwable ex) {
						throw new IllegalStateException(
								"Cannot load configuration class: " + beanDef.getBeanClassName(), ex);
					}
				}
			}
			if (ConfigurationClassUtils.CONFIGURATION_CLASS_FULL.equals(configClassAttr)) { // 当为@Configuration完全匹配配置类时
				if (!(beanDef instanceof AbstractBeanDefinition)) {
					throw new BeanDefinitionStoreException("Cannot enhance @Configuration bean definition '" +
							beanName + "' since it is not stored in an AbstractBeanDefinition subclass");
				}
				else if (logger.isInfoEnabled() && beanFactory.containsSingleton(beanName)) {
					logger.info("Cannot enhance @Configuration bean definition '" + beanName +
							"' since its singleton instance has been created too early. The typical cause " +
							"is a non-static @Bean method with a BeanDefinitionRegistryPostProcessor " +
							"return type: Consider declaring such methods as 'static'.");
				}
				configBeanDefs.put(beanName, (AbstractBeanDefinition) beanDef); // 放入容器
			}
		}
		if (configBeanDefs.isEmpty()) { // 当容器为空时直接返回，表示没有完全匹配配置类，不需要生成代理
			// nothing to enhance -> return immediately
			return;
		}

		ConfigurationClassEnhancer enhancer = new ConfigurationClassEnhancer(); // 创建ConfigurationClassEnhancer
		for (Map.Entry<String, AbstractBeanDefinition> entry : configBeanDefs.entrySet()) { // 遍历@Configuration完全匹配配置类
			AbstractBeanDefinition beanDef = entry.getValue(); // 获取完全匹配配置类的BeanDefinition
			// If a @Configuration class gets proxied, always proxy the target class
			beanDef.setAttribute(AutoProxyUtils.PRESERVE_TARGET_CLASS_ATTRIBUTE, Boolean.TRUE); // 设置preserveTargetClass属性
			// Set enhanced subclass of the user-specified bean class
			Class<?> configClass = beanDef.getBeanClass(); // 获取beanClass
			Class<?> enhancedClass = enhancer.enhance(configClass, this.beanClassLoader); // 生成代理类Class
			if (configClass != enhancedClass) {
				if (logger.isTraceEnabled()) {
					logger.trace(String.format("Replacing bean definition '%s' existing class '%s' with " +
							"enhanced class '%s'", entry.getKey(), configClass.getName(), enhancedClass.getName()));
				}
				beanDef.setBeanClass(enhancedClass); // 修改beanClass为代理类Class（交给Spring管理）
			}
		}
	}


	private static class ImportAwareBeanPostProcessor extends InstantiationAwareBeanPostProcessorAdapter {

		private final BeanFactory beanFactory;

		public ImportAwareBeanPostProcessor(BeanFactory beanFactory) { // 初始化ImportAwareBeanPostProcessor
			this.beanFactory = beanFactory;
		}

		@Override
		public PropertyValues postProcessProperties(@Nullable PropertyValues pvs, Object bean, String beanName) {
			// Inject the BeanFactory before AutowiredAnnotationBeanPostProcessor's
			// postProcessProperties method attempts to autowire other configuration beans.
			if (bean instanceof EnhancedConfiguration) { // 当为代理Bean时，设置BeanFactory
				((EnhancedConfiguration) bean).setBeanFactory(this.beanFactory); // 设置BeanFactory（会触发调用ConfigurationClassEnhancer.BeanFactoryAwareMethodInterceptor#intercept）
			}
			return pvs;
		}

		@Override
		public Object postProcessBeforeInitialization(Object bean, String beanName) { // 初始化前置操作--实现对ImportAware的支持
			if (bean instanceof ImportAware) { // 当bean实现类ImportAware接口时
				ImportRegistry ir = this.beanFactory.getBean(IMPORT_REGISTRY_BEAN_NAME, ImportRegistry.class); // 从容器中获取ImportRegistry类型的实例（只会有一个）
				AnnotationMetadata importingClass = ir.getImportingClassFor(ClassUtils.getUserClass(bean).getName()); // 从imports容器中获取导入该类的注解信息
				if (importingClass != null) {
					((ImportAware) bean).setImportMetadata(importingClass); // 设置导入类的注解信息
				}
			}
			return bean;
		}
	}

}
