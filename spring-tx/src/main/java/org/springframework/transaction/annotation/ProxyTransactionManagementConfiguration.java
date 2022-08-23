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

package org.springframework.transaction.annotation;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.transaction.config.TransactionManagementConfigUtils;
import org.springframework.transaction.interceptor.BeanFactoryTransactionAttributeSourceAdvisor;
import org.springframework.transaction.interceptor.TransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/**
 * {@code @Configuration} class that registers the Spring infrastructure beans
 * necessary to enable proxy-based annotation-driven transaction management.
 *
 * @author Chris Beams
 * @author Sebastien Deleuze
 * @since 3.1
 * @see EnableTransactionManagement
 * @see TransactionManagementConfigurationSelector
 */
@Configuration(proxyBeanMethods = false)
public class ProxyTransactionManagementConfiguration extends AbstractTransactionManagementConfiguration {

	@Bean(name = TransactionManagementConfigUtils.TRANSACTION_ADVISOR_BEAN_NAME)
	@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
	public BeanFactoryTransactionAttributeSourceAdvisor transactionAdvisor( // 注入事务切面类
			TransactionAttributeSource transactionAttributeSource, // 获取事务属性处理器
			TransactionInterceptor transactionInterceptor) { // 获取事务切面的Advice
		BeanFactoryTransactionAttributeSourceAdvisor advisor = new BeanFactoryTransactionAttributeSourceAdvisor(); // 创建事务切面类BeanFactoryTransactionAttributeSourceAdvisor
		advisor.setTransactionAttributeSource(transactionAttributeSource); // 设置事务属性处理器
		advisor.setAdvice(transactionInterceptor); // 设置切面的Advice
		if (this.enableTx != null) { // 根据@EnableTransactionManagement注解信息设置切面排序
			advisor.setOrder(this.enableTx.<Integer>getNumber("order")); // 设置切面排序
		}
		return advisor;
	}

	@Bean
	@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
	public TransactionAttributeSource transactionAttributeSource() { // 注入事务属性处理器
		return new AnnotationTransactionAttributeSource(); // 创建事务属性处理器（解析@Transaction事务注解并封装成TransactionAttribute对象）
	}

	@Bean
	@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
	public TransactionInterceptor transactionInterceptor( // 注入事务切面的Advice
			TransactionAttributeSource transactionAttributeSource) { // 获取事务属性处理器
		TransactionInterceptor interceptor = new TransactionInterceptor(); // 创建事务切面增强，实现了MethodInterceptor接口
		interceptor.setTransactionAttributeSource(transactionAttributeSource); // 设置事务属性处理器
		if (this.txManager != null) {
			interceptor.setTransactionManager(this.txManager); // 设置事务管理器（程序启动时不一定需要设置，在调用的过程中会获取到）
		}
		return interceptor;
	}

}
