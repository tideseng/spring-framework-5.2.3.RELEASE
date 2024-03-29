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

import java.io.Serializable;
import java.lang.reflect.AnnotatedElement;
import java.util.ArrayList;
import java.util.List;

import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.lang.Nullable;
import org.springframework.transaction.interceptor.NoRollbackRuleAttribute;
import org.springframework.transaction.interceptor.RollbackRuleAttribute;
import org.springframework.transaction.interceptor.RuleBasedTransactionAttribute;
import org.springframework.transaction.interceptor.TransactionAttribute;

/**
 * Strategy implementation for parsing Spring's {@link Transactional} annotation.
 *
 * @author Juergen Hoeller
 * @since 2.5
 */
@SuppressWarnings("serial")
public class SpringTransactionAnnotationParser implements TransactionAnnotationParser, Serializable {

	@Override
	public boolean isCandidateClass(Class<?> targetClass) { // 判断是否为候选Class（默认返回true）
		return AnnotationUtils.isCandidateClass(targetClass, Transactional.class); // 判断是否为候选Class（默认返回true）
	}

	@Override
	@Nullable
	public TransactionAttribute parseTransactionAnnotation(AnnotatedElement element) { // 获取事务属性
		AnnotationAttributes attributes = AnnotatedElementUtils.findMergedAnnotationAttributes( // 获取注解属性
				element, Transactional.class, false, false);
		if (attributes != null) {
			return parseTransactionAnnotation(attributes); // 获取事务属性
		}
		else {
			return null;
		}
	}

	public TransactionAttribute parseTransactionAnnotation(Transactional ann) {
		return parseTransactionAnnotation(AnnotationUtils.getAnnotationAttributes(ann, false, false));
	}

	protected TransactionAttribute parseTransactionAnnotation(AnnotationAttributes attributes) { // 获取事务属性
		RuleBasedTransactionAttribute rbta = new RuleBasedTransactionAttribute(); // 创建事务属性RuleBasedTransactionAttribute
		// 封装事务属性
		Propagation propagation = attributes.getEnum("propagation");
		rbta.setPropagationBehavior(propagation.value()); // 设置传播属性
		Isolation isolation = attributes.getEnum("isolation");
		rbta.setIsolationLevel(isolation.value()); // 设置隔离级别
		rbta.setTimeout(attributes.getNumber("timeout").intValue()); // 设置超时时间
		rbta.setReadOnly(attributes.getBoolean("readOnly")); // 设置是否只读
		rbta.setQualifier(attributes.getString("value"));
		// 封装异常回滚规则
		List<RollbackRuleAttribute> rollbackRules = new ArrayList<>();
		for (Class<?> rbRule : attributes.getClassArray("rollbackFor")) {
			rollbackRules.add(new RollbackRuleAttribute(rbRule)); // 创建RollbackRuleAttribute
		}
		for (String rbRule : attributes.getStringArray("rollbackForClassName")) {
			rollbackRules.add(new RollbackRuleAttribute(rbRule)); // 创建RollbackRuleAttribute
		}
		for (Class<?> rbRule : attributes.getClassArray("noRollbackFor")) {
			rollbackRules.add(new NoRollbackRuleAttribute(rbRule)); // 创建NoRollbackRuleAttribute
		}
		for (String rbRule : attributes.getStringArray("noRollbackForClassName")) {
			rollbackRules.add(new NoRollbackRuleAttribute(rbRule)); // 创建NoRollbackRuleAttribute
		}
		rbta.setRollbackRules(rollbackRules); // 设置异常回滚规则

		return rbta;
	}


	@Override
	public boolean equals(@Nullable Object other) {
		return (this == other || other instanceof SpringTransactionAnnotationParser);
	}

	@Override
	public int hashCode() {
		return SpringTransactionAnnotationParser.class.hashCode();
	}

}
