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

package org.springframework.transaction.interceptor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.lang.Nullable;

/**
 * TransactionAttribute implementation that works out whether a given exception
 * should cause transaction rollback by applying a number of rollback rules,
 * both positive and negative. If no rules are relevant to the exception, it
 * behaves like DefaultTransactionAttribute (rolling back on runtime exceptions).
 *
 * <p>{@link TransactionAttributeEditor} creates objects of this class.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @since 09.04.2003
 * @see TransactionAttributeEditor
 */
@SuppressWarnings("serial")
public class RuleBasedTransactionAttribute extends DefaultTransactionAttribute implements Serializable {

	/** Prefix for rollback-on-exception rules in description strings. */
	public static final String PREFIX_ROLLBACK_RULE = "-";

	/** Prefix for commit-on-exception rules in description strings. */
	public static final String PREFIX_COMMIT_RULE = "+";


	/** Static for optimal serializability. */
	private static final Log logger = LogFactory.getLog(RuleBasedTransactionAttribute.class);

	@Nullable
	private List<RollbackRuleAttribute> rollbackRules;


	/**
	 * Create a new RuleBasedTransactionAttribute, with default settings.
	 * Can be modified through bean property setters.
	 * @see #setPropagationBehavior
	 * @see #setIsolationLevel
	 * @see #setTimeout
	 * @see #setReadOnly
	 * @see #setName
	 * @see #setRollbackRules
	 */
	public RuleBasedTransactionAttribute() { // 创建事务属性RuleBasedTransactionAttribute
		super();
	}

	/**
	 * Copy constructor. Definition can be modified through bean property setters.
	 * @see #setPropagationBehavior
	 * @see #setIsolationLevel
	 * @see #setTimeout
	 * @see #setReadOnly
	 * @see #setName
	 * @see #setRollbackRules
	 */
	public RuleBasedTransactionAttribute(RuleBasedTransactionAttribute other) {
		super(other);
		this.rollbackRules = (other.rollbackRules != null ? new ArrayList<>(other.rollbackRules) : null);
	}

	/**
	 * Create a new DefaultTransactionAttribute with the given
	 * propagation behavior. Can be modified through bean property setters.
	 * @param propagationBehavior one of the propagation constants in the
	 * TransactionDefinition interface
	 * @param rollbackRules the list of RollbackRuleAttributes to apply
	 * @see #setIsolationLevel
	 * @see #setTimeout
	 * @see #setReadOnly
	 */
	public RuleBasedTransactionAttribute(int propagationBehavior, List<RollbackRuleAttribute> rollbackRules) {
		super(propagationBehavior);
		this.rollbackRules = rollbackRules;
	}


	/**
	 * Set the list of {@code RollbackRuleAttribute} objects
	 * (and/or {@code NoRollbackRuleAttribute} objects) to apply.
	 * @see RollbackRuleAttribute
	 * @see NoRollbackRuleAttribute
	 */
	public void setRollbackRules(List<RollbackRuleAttribute> rollbackRules) { // 设置异常回滚规则
		this.rollbackRules = rollbackRules;
	}

	/**
	 * Return the list of {@code RollbackRuleAttribute} objects
	 * (never {@code null}).
	 */
	public List<RollbackRuleAttribute> getRollbackRules() {
		if (this.rollbackRules == null) {
			this.rollbackRules = new LinkedList<>();
		}
		return this.rollbackRules;
	}


	/**
	 * Winning rule is the shallowest rule (that is, the closest in the
	 * inheritance hierarchy to the exception). If no rule applies (-1),
	 * return false.
	 * @see TransactionAttribute#rollbackOn(java.lang.Throwable)
	 */
	@Override
	public boolean rollbackOn(Throwable ex) { // 判断事务回滚异常类型
		if (logger.isTraceEnabled()) {
			logger.trace("Applying rules to determine whether transaction should rollback on " + ex);
		}

		RollbackRuleAttribute winner = null; // 异常回滚规则
		int deepest = Integer.MAX_VALUE;

		if (this.rollbackRules != null) {
			for (RollbackRuleAttribute rule : this.rollbackRules) { // 遍历异常回滚规则
				int depth = rule.getDepth(ex); // 是否匹配到异常回滚规则（-1表示未匹配到）
				if (depth >= 0 && depth < deepest) {
					deepest = depth;
					winner = rule; // 匹配到的异常回滚规则
				}
			}
		}

		if (logger.isTraceEnabled()) {
			logger.trace("Winning rollback rule is: " + winner);
		}

		// User superclass behavior (rollback on unchecked) if no rule matches.
		if (winner == null) { // 未匹配到异常回滚规则时，调用父类方法进行匹配
			logger.trace("No relevant rollback rule found: applying default rules");
			return super.rollbackOn(ex); // 调用父类方法（判断是否为RuntimeException或Error异常）
		}

		return !(winner instanceof NoRollbackRuleAttribute); // 匹配到异常回滚规则时，判断是否需要回滚
	}


	@Override
	public String toString() {
		StringBuilder result = getAttributeDescription();
		if (this.rollbackRules != null) {
			for (RollbackRuleAttribute rule : this.rollbackRules) {
				String sign = (rule instanceof NoRollbackRuleAttribute ? PREFIX_COMMIT_RULE : PREFIX_ROLLBACK_RULE);
				result.append(',').append(sign).append(rule.getExceptionName());
			}
		}
		return result.toString();
	}

}
