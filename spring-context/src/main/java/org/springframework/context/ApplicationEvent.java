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

package org.springframework.context;

import java.util.EventObject;

/**
 * Class to be extended by all application events. Abstract as it
 * doesn't make sense for generic events to be published directly.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @see org.springframework.context.ApplicationListener
 * @see org.springframework.context.event.EventListener
 */
public abstract class ApplicationEvent extends EventObject { // 应用事件的基类

	/** use serialVersionUID from Spring 1.2 for interoperability. */
	private static final long serialVersionUID = 7099057708183571937L;

	/** System time when the event happened. */
	private final long timestamp; // 事件发生时间


	/**
	 * Create a new {@code ApplicationEvent}.
	 * @param source the object on which the event initially occurred or with
	 * which the event is associated (never {@code null})
	 */
	public ApplicationEvent(Object source) { // 应用事件构造方法，所有的子类应用事件都需调用父类构造函数传入事件内容
		super(source);
		this.timestamp = System.currentTimeMillis();
	}


	/**
	 * Return the system time in milliseconds when the event occurred.
	 */
	public final long getTimestamp() {
		return this.timestamp;
	}

}
