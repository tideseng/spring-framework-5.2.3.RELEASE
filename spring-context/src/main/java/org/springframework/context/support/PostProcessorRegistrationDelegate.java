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

package org.springframework.context.support;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.MergedBeanDefinitionPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.OrderComparator;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.lang.Nullable;

/**
 * Delegate for AbstractApplicationContext's post-processor handling.
 *
 * @author Juergen Hoeller
 * @since 4.0
 */
final class PostProcessorRegistrationDelegate {

	private PostProcessorRegistrationDelegate() {
	}


	public static void invokeBeanFactoryPostProcessors( // 执行自定义和内置的BeanFactoryPostProcessor
			ConfigurableListableBeanFactory beanFactory, List<BeanFactoryPostProcessor> beanFactoryPostProcessors) {

		// Invoke BeanDefinitionRegistryPostProcessors first, if any.
		Set<String> processedBeans = new HashSet<>(); // 已处理过的容器
		// 按照优先级顺序调用BeanDefinitionRegistryPostProcessor实现类的回调方法
		if (beanFactory instanceof BeanDefinitionRegistry) { // 当BeanFactory实现了BeanDefinitionRegistry接口
			BeanDefinitionRegistry registry = (BeanDefinitionRegistry) beanFactory;
			List<BeanFactoryPostProcessor> regularPostProcessors = new ArrayList<>(); // 定义BeanFactoryPostProcessor实例容器
			List<BeanDefinitionRegistryPostProcessor> registryProcessors = new ArrayList<>(); // 定义BeanDefinitionRegistryPostProcessor实例容器

			for (BeanFactoryPostProcessor postProcessor : beanFactoryPostProcessors) { // 默认当前BeanFactory中的beanFactoryPostProcessors集合为空
				if (postProcessor instanceof BeanDefinitionRegistryPostProcessor) { // 当对象实现了BeanDefinitionRegistryPostProcessor时
					BeanDefinitionRegistryPostProcessor registryProcessor =
							(BeanDefinitionRegistryPostProcessor) postProcessor;
					registryProcessor.postProcessBeanDefinitionRegistry(registry); // 调用BeanDefinitionRegistryPostProcessor实现类的postProcessBeanDefinitionRegistry方法
					registryProcessors.add(registryProcessor); // 将对象放入BeanDefinitionRegistryPostProcessor实例容器
				}
				else {
					regularPostProcessors.add(postProcessor); // 当对象未实现BeanDefinitionRegistryPostProcessor时，将对象放入BeanFactoryPostProcessor实例容器
				}
			}

			// Do not initialize FactoryBeans here: We need to leave all regular beans
			// uninitialized to let the bean factory post-processors apply to them!
			// Separate between BeanDefinitionRegistryPostProcessors that implement
			// PriorityOrdered, Ordered, and the rest.
			List<BeanDefinitionRegistryPostProcessor> currentRegistryProcessors = new ArrayList<>(); // 定义BeanDefinitionRegistryPostProcessor实例临时容器
			// 1.调用实现了PriorityOrdered接口的BeanDefinitionRegistryPostProcessor实现类的postProcessBeanDefinitionRegistry方法
			// First, invoke the BeanDefinitionRegistryPostProcessors that implement PriorityOrdered.
			String[] postProcessorNames =
					beanFactory.getBeanNamesForType(BeanDefinitionRegistryPostProcessor.class, true, false); // 获取所有实现了BeanDefinitionRegistryPostProcessor接口的beanName
			for (String ppName : postProcessorNames) {
				if (beanFactory.isTypeMatch(ppName, PriorityOrdered.class)) { // 判断是否实现了PriorityOrdered接口
					currentRegistryProcessors.add(beanFactory.getBean(ppName, BeanDefinitionRegistryPostProcessor.class)); // 实例化BeanDefinitionRegistryPostProcessor实现类
					processedBeans.add(ppName); // 添加到已处理容器中
				}
			}
			sortPostProcessors(currentRegistryProcessors, beanFactory); // 对临时容器进行排序
			registryProcessors.addAll(currentRegistryProcessors); // 将临时容器的元素添加到BeanDefinitionRegistryPostProcessor实例容器中
			invokeBeanDefinitionRegistryPostProcessors(currentRegistryProcessors, registry); // 调用BeanDefinitionRegistryPostProcessor实现类的postProcessBeanDefinitionRegistry方法（首次启动时这里通常是ConfigurationClassPostProcessor）
			currentRegistryProcessors.clear(); // 清空临时容器
			// 2.调用实现了Ordered接口的BeanDefinitionRegistryPostProcessor实现类的postProcessBeanDefinitionRegistry方法
			// Next, invoke the BeanDefinitionRegistryPostProcessors that implement Ordered.
			postProcessorNames = beanFactory.getBeanNamesForType(BeanDefinitionRegistryPostProcessor.class, true, false); // 获取所有实现了BeanDefinitionRegistryPostProcessor接口的beanName
			for (String ppName : postProcessorNames) {
				if (!processedBeans.contains(ppName) && beanFactory.isTypeMatch(ppName, Ordered.class)) { // 判断是否实现了Ordered接口（排除已处理过的Bean）
					currentRegistryProcessors.add(beanFactory.getBean(ppName, BeanDefinitionRegistryPostProcessor.class)); // 实例化BeanDefinitionRegistryPostProcessor实现类
					processedBeans.add(ppName); // 添加到已处理容器中
				}
			}
			sortPostProcessors(currentRegistryProcessors, beanFactory); // 对临时容器进行排序
			registryProcessors.addAll(currentRegistryProcessors); // 将临时容器的元素添加到BeanDefinitionRegistryPostProcessor实例容器中
			invokeBeanDefinitionRegistryPostProcessors(currentRegistryProcessors, registry); // 调用BeanDefinitionRegistryPostProcessor实现类的postProcessBeanDefinitionRegistry方法
			currentRegistryProcessors.clear(); // 清空临时容器
			// 3.调用未实现PriorityOrdered、Ordered接口的BeanDefinitionRegistryPostProcessor实现类的postProcessBeanDefinitionRegistry方法
			// Finally, invoke all other BeanDefinitionRegistryPostProcessors until no further ones appear.
			boolean reiterate = true;
			while (reiterate) {
				reiterate = false;
				postProcessorNames = beanFactory.getBeanNamesForType(BeanDefinitionRegistryPostProcessor.class, true, false); // 获取所有实现了BeanDefinitionRegistryPostProcessor接口的beanName
				for (String ppName : postProcessorNames) {
					if (!processedBeans.contains(ppName)) { // 排除已处理过的Bean
						currentRegistryProcessors.add(beanFactory.getBean(ppName, BeanDefinitionRegistryPostProcessor.class)); // 实例化BeanDefinitionRegistryPostProcessor实现类
						processedBeans.add(ppName); // 添加到已处理容器中
						reiterate = true;
					}
				}
				sortPostProcessors(currentRegistryProcessors, beanFactory);
				registryProcessors.addAll(currentRegistryProcessors); // 将临时容器的元素添加到BeanDefinitionRegistryPostProcessor实例容器中
				invokeBeanDefinitionRegistryPostProcessors(currentRegistryProcessors, registry); // 调用BeanDefinitionRegistryPostProcessor实现类的postProcessBeanDefinitionRegistry方法
				currentRegistryProcessors.clear(); // 清空临时容器
			}

			// Now, invoke the postProcessBeanFactory callback of all processors handled so far.
			invokeBeanFactoryPostProcessors(registryProcessors, beanFactory); // 调用BeanDefinitionRegistryPostProcessor实现类的postProcessBeanFactory方法
			invokeBeanFactoryPostProcessors(regularPostProcessors, beanFactory); // 调用BeanFactoryPostProcessor实现类的postProcessBeanFactory方法
		}

		else { // 当BeanFactory未实现BeanDefinitionRegistry接口
			// Invoke factory processors registered with the context instance.
			invokeBeanFactoryPostProcessors(beanFactoryPostProcessors, beanFactory); // 调用BeanFactoryPostProcessor实现类的postProcessBeanFactory方法
		}
		// 按照优先级顺序调用BeanFactoryPostProcessor实现类的回调方法
		// Do not initialize FactoryBeans here: We need to leave all regular beans
		// uninitialized to let the bean factory post-processors apply to them!
		String[] postProcessorNames =
				beanFactory.getBeanNamesForType(BeanFactoryPostProcessor.class, true, false); // 获取所有实现了BeanFactoryPostProcessor接口的beanName

		// Separate between BeanFactoryPostProcessors that implement PriorityOrdered,
		// Ordered, and the rest.
		List<BeanFactoryPostProcessor> priorityOrderedPostProcessors = new ArrayList<>(); // 定义实现了PriorityOrdered接口的BeanFactoryPostProcessor实例容器
		List<String> orderedPostProcessorNames = new ArrayList<>(); // 定义实现了Ordered接口的beanName
		List<String> nonOrderedPostProcessorNames = new ArrayList<>(); // 定义未实现PriorityOrdered、Ordered接口的beanName
		for (String ppName : postProcessorNames) {
			if (processedBeans.contains(ppName)) { // 排除已处理过的Bean
				// skip - already processed in first phase above
			}
			else if (beanFactory.isTypeMatch(ppName, PriorityOrdered.class)) {
				priorityOrderedPostProcessors.add(beanFactory.getBean(ppName, BeanFactoryPostProcessor.class)); // 实例化实现了PriorityOrdered接口的BeanFactoryPostProcessor实现类
			}
			else if (beanFactory.isTypeMatch(ppName, Ordered.class)) {
				orderedPostProcessorNames.add(ppName);
			}
			else {
				nonOrderedPostProcessorNames.add(ppName);
			}
		}
		// 1.调用实现了PriorityOrdered接口的BeanFactoryPostProcessor实现类的postProcessBeanFactory方法
		// First, invoke the BeanFactoryPostProcessors that implement PriorityOrdered.
		sortPostProcessors(priorityOrderedPostProcessors, beanFactory); // 对实现了PriorityOrdered接口的BeanFactoryPostProcessor实例进行排序
		invokeBeanFactoryPostProcessors(priorityOrderedPostProcessors, beanFactory); // 调用BeanFactoryPostProcessor实现类的postProcessBeanFactory方法
		// 2.调用实现了Ordered接口的BeanFactoryPostProcessor实现类的postProcessBeanFactory方法
		// Next, invoke the BeanFactoryPostProcessors that implement Ordered.
		List<BeanFactoryPostProcessor> orderedPostProcessors = new ArrayList<>(orderedPostProcessorNames.size());
		for (String postProcessorName : orderedPostProcessorNames) {
			orderedPostProcessors.add(beanFactory.getBean(postProcessorName, BeanFactoryPostProcessor.class)); // 实例化实现了Ordered接口的BeanFactoryPostProcessor实现类
		}
		sortPostProcessors(orderedPostProcessors, beanFactory); // 对实现了Ordered接口的BeanFactoryPostProcessor实例进行排序
		invokeBeanFactoryPostProcessors(orderedPostProcessors, beanFactory); // 调用BeanFactoryPostProcessor实现类的postProcessBeanFactory方法
		// 3.调用未实现PriorityOrdered、Ordered接口的BeanFactoryPostProcessor实现类的postProcessBeanFactory方法
		// Finally, invoke all other BeanFactoryPostProcessors.
		List<BeanFactoryPostProcessor> nonOrderedPostProcessors = new ArrayList<>(nonOrderedPostProcessorNames.size());
		for (String postProcessorName : nonOrderedPostProcessorNames) {
			nonOrderedPostProcessors.add(beanFactory.getBean(postProcessorName, BeanFactoryPostProcessor.class)); // 实例化未实现PriorityOrdered、Ordered接口的BeanFactoryPostProcessor实现类
		}
		invokeBeanFactoryPostProcessors(nonOrderedPostProcessors, beanFactory); // 调用BeanFactoryPostProcessor实现类的postProcessBeanFactory方法

		// Clear cached merged bean definitions since the post-processors might have
		// modified the original metadata, e.g. replacing placeholders in values...
		beanFactory.clearMetadataCache();
	}

	public static void registerBeanPostProcessors( // 注册beanPostProcessor实现类（如：AutowiredAnnotationBeanPostProcessor、CommonAnnotationBeanPostProcessor）
			ConfigurableListableBeanFactory beanFactory, AbstractApplicationContext applicationContext) {
		// 获取所有实现了BeanPostProcessor接口的beanName
		String[] postProcessorNames = beanFactory.getBeanNamesForType(BeanPostProcessor.class, true, false);

		// Register BeanPostProcessorChecker that logs an info message when
		// a bean is created during BeanPostProcessor instantiation, i.e. when
		// a bean is not eligible for getting processed by all BeanPostProcessors.
		int beanProcessorTargetCount = beanFactory.getBeanPostProcessorCount() + 1 + postProcessorNames.length;
		beanFactory.addBeanPostProcessor(new BeanPostProcessorChecker(beanFactory, beanProcessorTargetCount)); // 4.实例化实现了BeanPostProcessor接口的BeanPostProcessorChecker，并添加到BeanFactory容器中

		// Separate between BeanPostProcessors that implement PriorityOrdered,
		// Ordered, and the rest.
		List<BeanPostProcessor> priorityOrderedPostProcessors = new ArrayList<>(); // 定义实现了PriorityOrdered接口的BeanPostProcessor实例容器
		List<BeanPostProcessor> internalPostProcessors = new ArrayList<>(); // 定义内置的BeanPostProcessor实例容器
		List<String> orderedPostProcessorNames = new ArrayList<>(); // 定义实现了Ordered接口的beanName
		List<String> nonOrderedPostProcessorNames = new ArrayList<>(); // 定义未实现PriorityOrdered、Ordered接口的beanName
		for (String ppName : postProcessorNames) { // 遍历beanName
			if (beanFactory.isTypeMatch(ppName, PriorityOrdered.class)) {
				BeanPostProcessor pp = beanFactory.getBean(ppName, BeanPostProcessor.class); // 实例化实现了PriorityOrdered接口的BeanFactoryPostProcessor实现类（是AutowiredAnnotationBeanPostProcessor、CommonAnnotationBeanPostProcessor创建的入口）
				priorityOrderedPostProcessors.add(pp);
				if (pp instanceof MergedBeanDefinitionPostProcessor) {
					internalPostProcessors.add(pp); // 添加内置的BeanFactoryPostProcessor实现类
				}
			}
			else if (beanFactory.isTypeMatch(ppName, Ordered.class)) {
				orderedPostProcessorNames.add(ppName);
			}
			else {
				nonOrderedPostProcessorNames.add(ppName);
			}
		}

		// First, register the BeanPostProcessors that implement PriorityOrdered.
		sortPostProcessors(priorityOrderedPostProcessors, beanFactory); // 排序
		registerBeanPostProcessors(beanFactory, priorityOrderedPostProcessors); // 将实现了PriorityOrdered接口的BeanPostProcessor实现类注册到BeanFactory容器中（按批次一起注册）

		// Next, register the BeanPostProcessors that implement Ordered.
		List<BeanPostProcessor> orderedPostProcessors = new ArrayList<>(orderedPostProcessorNames.size());
		for (String ppName : orderedPostProcessorNames) {
			BeanPostProcessor pp = beanFactory.getBean(ppName, BeanPostProcessor.class); // 实例化实现了Ordered接口的BeanFactoryPostProcessor实现类
			orderedPostProcessors.add(pp);
			if (pp instanceof MergedBeanDefinitionPostProcessor) {
				internalPostProcessors.add(pp); // 添加内置的BeanFactoryPostProcessor实现类
			}
		}
		sortPostProcessors(orderedPostProcessors, beanFactory); // 排序
		registerBeanPostProcessors(beanFactory, orderedPostProcessors); // 将实现了Ordered接口的BeanPostProcessor实现类注册到BeanFactory容器中（按批次一起注册）

		// Now, register all regular BeanPostProcessors.
		List<BeanPostProcessor> nonOrderedPostProcessors = new ArrayList<>(nonOrderedPostProcessorNames.size());
		for (String ppName : nonOrderedPostProcessorNames) {
			BeanPostProcessor pp = beanFactory.getBean(ppName, BeanPostProcessor.class); // 实例化未实现PriorityOrdered、Ordered接口的BeanFactoryPostProcessor实现类
			nonOrderedPostProcessors.add(pp);
			if (pp instanceof MergedBeanDefinitionPostProcessor) {
				internalPostProcessors.add(pp); // 添加内置的BeanFactoryPostProcessor实现类
			}
		}
		registerBeanPostProcessors(beanFactory, nonOrderedPostProcessors); // 将未实现PriorityOrdered、Ordered接口的BeanPostProcessor实现类注册到BeanFactory容器中（按批次一起注册）

		// Finally, re-register all internal BeanPostProcessors.
		sortPostProcessors(internalPostProcessors, beanFactory); // 排序
		registerBeanPostProcessors(beanFactory, internalPostProcessors); // 将内置的BeanPostProcessor实现类注册到BeanFactory容器中（因为自定义的比内置的先加入容器，所以自定义的优先级要高于内置的CommonAnnotationBeanPostProcessor、AutowiredAnnotationBeanPostProcessor）

		// Re-register post-processor for detecting inner beans as ApplicationListeners,
		// moving it to the end of the processor chain (for picking up proxies etc).
		beanFactory.addBeanPostProcessor(new ApplicationListenerDetector(applicationContext)); // 实例化实现了BeanPostProcessor接口的ApplicationListenerDetector，并添加到BeanFactory容器中（实际上已添加过了，重新添加到队尾而已）
	}

	private static void sortPostProcessors(List<?> postProcessors, ConfigurableListableBeanFactory beanFactory) {
		Comparator<Object> comparatorToUse = null;
		if (beanFactory instanceof DefaultListableBeanFactory) {
			comparatorToUse = ((DefaultListableBeanFactory) beanFactory).getDependencyComparator();
		}
		if (comparatorToUse == null) {
			comparatorToUse = OrderComparator.INSTANCE;
		}
		postProcessors.sort(comparatorToUse);
	}

	/**
	 * Invoke the given BeanDefinitionRegistryPostProcessor beans.
	 */
	private static void invokeBeanDefinitionRegistryPostProcessors( // 调用BeanDefinitionRegistryPostProcessor实现类的postProcessBeanDefinitionRegistry方法
			Collection<? extends BeanDefinitionRegistryPostProcessor> postProcessors, BeanDefinitionRegistry registry) {

		for (BeanDefinitionRegistryPostProcessor postProcessor : postProcessors) {
			postProcessor.postProcessBeanDefinitionRegistry(registry); // 调用BeanDefinitionRegistryPostProcessor实现类的postProcessBeanDefinitionRegistry方法
		}
	}

	/**
	 * Invoke the given BeanFactoryPostProcessor beans.
	 */
	private static void invokeBeanFactoryPostProcessors( // 调用BeanFactoryPostProcessor实现类的postProcessBeanFactory方法
			Collection<? extends BeanFactoryPostProcessor> postProcessors, ConfigurableListableBeanFactory beanFactory) {

		for (BeanFactoryPostProcessor postProcessor : postProcessors) {
			postProcessor.postProcessBeanFactory(beanFactory); // 调用BeanFactoryPostProcessor实现类的postProcessBeanFactory方法
		}
	}

	/**
	 * Register the given BeanPostProcessor beans.
	 */
	private static void registerBeanPostProcessors( // 将BeanPostProcessor实现类注册到BeanFactory容器中
			ConfigurableListableBeanFactory beanFactory, List<BeanPostProcessor> postProcessors) {

		for (BeanPostProcessor postProcessor : postProcessors) {
			beanFactory.addBeanPostProcessor(postProcessor);
		}
	}


	/**
	 * BeanPostProcessor that logs an info message when a bean is created during
	 * BeanPostProcessor instantiation, i.e. when a bean is not eligible for
	 * getting processed by all BeanPostProcessors.
	 */
	private static final class BeanPostProcessorChecker implements BeanPostProcessor {

		private static final Log logger = LogFactory.getLog(BeanPostProcessorChecker.class);

		private final ConfigurableListableBeanFactory beanFactory;

		private final int beanPostProcessorTargetCount;

		public BeanPostProcessorChecker(ConfigurableListableBeanFactory beanFactory, int beanPostProcessorTargetCount) {
			this.beanFactory = beanFactory;
			this.beanPostProcessorTargetCount = beanPostProcessorTargetCount;
		}

		@Override
		public Object postProcessBeforeInitialization(Object bean, String beanName) {
			return bean;
		}

		@Override
		public Object postProcessAfterInitialization(Object bean, String beanName) {
			if (!(bean instanceof BeanPostProcessor) && !isInfrastructureBean(beanName) &&
					this.beanFactory.getBeanPostProcessorCount() < this.beanPostProcessorTargetCount) {
				if (logger.isInfoEnabled()) {
					logger.info("Bean '" + beanName + "' of type [" + bean.getClass().getName() +
							"] is not eligible for getting processed by all BeanPostProcessors " +
							"(for example: not eligible for auto-proxying)");
				}
			}
			return bean;
		}

		private boolean isInfrastructureBean(@Nullable String beanName) {
			if (beanName != null && this.beanFactory.containsBeanDefinition(beanName)) {
				BeanDefinition bd = this.beanFactory.getBeanDefinition(beanName);
				return (bd.getRole() == RootBeanDefinition.ROLE_INFRASTRUCTURE);
			}
			return false;
		}
	}

}
