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

package org.springframework.transaction.interceptor;

import java.io.Serializable;
import java.lang.reflect.Method;

import org.springframework.aop.ClassFilter;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.dao.support.PersistenceExceptionTranslator;
import org.springframework.lang.Nullable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.ObjectUtils;

/**
 * Inner class that implements a Pointcut that matches if the underlying
 * {@link TransactionAttributeSource} has an attribute for a given method.
 *
 * @author Juergen Hoeller
 * @since 2.5.5
 */
@SuppressWarnings("serial")
abstract class TransactionAttributeSourcePointcut extends StaticMethodMatcherPointcut implements Serializable { // 事务Pointcut

	protected TransactionAttributeSourcePointcut() { // 创建事务Pointcut
		setClassFilter(new TransactionAttributeSourceClassFilter()); // 设置ClassFilter，覆盖默认的ClassFilter，但类级别的匹配基本没校验
	}


	@Override
	public boolean matches(Method method, Class<?> targetClass) { // 方法级别的匹配
		TransactionAttributeSource tas = getTransactionAttributeSource(); // 获取事务属性处理器
		return (tas == null || tas.getTransactionAttribute(method, targetClass) != null); // 判断方法上或类上的事务属性不为空（不为空时生成代理）
	}

	@Override
	public boolean equals(@Nullable Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof TransactionAttributeSourcePointcut)) {
			return false;
		}
		TransactionAttributeSourcePointcut otherPc = (TransactionAttributeSourcePointcut) other;
		return ObjectUtils.nullSafeEquals(getTransactionAttributeSource(), otherPc.getTransactionAttributeSource());
	}

	@Override
	public int hashCode() {
		return TransactionAttributeSourcePointcut.class.hashCode();
	}

	@Override
	public String toString() {
		return getClass().getName() + ": " + getTransactionAttributeSource();
	}


	/**
	 * Obtain the underlying TransactionAttributeSource (may be {@code null}).
	 * To be implemented by subclasses.
	 */
	@Nullable
	protected abstract TransactionAttributeSource getTransactionAttributeSource(); // 抽象方法，由子类实现


	/**
	 * {@link ClassFilter} that delegates to {@link TransactionAttributeSource#isCandidateClass}
	 * for filtering classes whose methods are not worth searching to begin with.
	 */
	private class TransactionAttributeSourceClassFilter implements ClassFilter { // 事务的ClassFilter（类级别的匹配基本没校验）

		@Override
		public boolean matches(Class<?> clazz) { // 匹配类（基本没进行校验）
			if (TransactionalProxy.class.isAssignableFrom(clazz) ||
					PlatformTransactionManager.class.isAssignableFrom(clazz) ||
					PersistenceExceptionTranslator.class.isAssignableFrom(clazz)) {
				return false;
			}
			TransactionAttributeSource tas = getTransactionAttributeSource(); // 获取事务属性处理器
			return (tas == null || tas.isCandidateClass(clazz)); // 默认返回true
		}
	}

}
