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

package org.springframework.web.method.annotation;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.core.ExceptionDepthComparator;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ConcurrentReferenceHashMap;
import org.springframework.util.ReflectionUtils.MethodFilter;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Discovers {@linkplain ExceptionHandler @ExceptionHandler} methods in a given class,
 * including all of its superclasses, and helps to resolve a given {@link Exception}
 * to the exception types supported by a given {@link Method}.
 *
 * @author Rossen Stoyanchev
 * @author Juergen Hoeller
 * @since 3.1
 */
public class ExceptionHandlerMethodResolver { // @ExceptionHandler注解方法解析器

	/**
	 * A filter for selecting {@code @ExceptionHandler} methods.
	 */
	public static final MethodFilter EXCEPTION_HANDLER_METHODS = method ->
			AnnotatedElementUtils.hasAnnotation(method, ExceptionHandler.class); // 方法过滤条件--需要有@ExceptionHandler注解


	private final Map<Class<? extends Throwable>, Method> mappedMethods = new HashMap<>(16); // 异常类与方法的映射关系（一级缓存）

	private final Map<Class<? extends Throwable>, Method> exceptionLookupCache = new ConcurrentReferenceHashMap<>(16); // 异常类与匹配成功的方法映射关系（二级缓存）


	/**
	 * A constructor that finds {@link ExceptionHandler} methods in the given type.
	 * @param handlerType the type to introspect
	 */
	public ExceptionHandlerMethodResolver(Class<?> handlerType) { // 从给定的类中解析@ExceptionHandler注解修饰的方法，并添加异常类与对应方法的映射关系
		for (Method method : MethodIntrospector.selectMethods(handlerType, EXCEPTION_HANDLER_METHODS)) { // 遍历class中有@ExceptionHandler注解修饰的方法
			for (Class<? extends Throwable> exceptionType : detectExceptionMappings(method)) { // 遍历方法上@ExceptionHandler注解定义的异常类
				addExceptionMapping(exceptionType, method); // 添加异常类与对应方法的映射关系
			}
		}
	}


	/**
	 * Extract exception mappings from the {@code @ExceptionHandler} annotation first,
	 * and then as a fallback from the method signature itself.
	 */
	@SuppressWarnings("unchecked")
	private List<Class<? extends Throwable>> detectExceptionMappings(Method method) { // 获取方法上@ExceptionHandler注解定义的异常类列表
		List<Class<? extends Throwable>> result = new ArrayList<>();
		detectAnnotationExceptionMappings(method, result); // 获取方法上@ExceptionHandler注解定义的异常类列表
		if (result.isEmpty()) { // 当@ExceptionHandler注解未定义异常类时，从方法参数中获取
			for (Class<?> paramType : method.getParameterTypes()) { // 遍历@ExceptionHandler注解修饰的方法参数
				if (Throwable.class.isAssignableFrom(paramType)) { // 当参数类型属于Throwable类型时
					result.add((Class<? extends Throwable>) paramType); // 添加解析到的异常类
				}
			}
		}
		if (result.isEmpty()) {
			throw new IllegalStateException("No exception types mapped to " + method);
		}
		return result;
	}

	private void detectAnnotationExceptionMappings(Method method, List<Class<? extends Throwable>> result) { // 获取方法上@ExceptionHandler注解定义的异常类列表
		ExceptionHandler ann = AnnotatedElementUtils.findMergedAnnotation(method, ExceptionHandler.class);
		Assert.state(ann != null, "No ExceptionHandler annotation");
		result.addAll(Arrays.asList(ann.value())); // 添加解析到的异常类
	}

	private void addExceptionMapping(Class<? extends Throwable> exceptionType, Method method) { // 添加异常类与对应方法的映射关系
		Method oldMethod = this.mappedMethods.put(exceptionType, method); // 将异常类与对应方法的映射关系放入mappedMethods容器
		if (oldMethod != null && !oldMethod.equals(method)) {
			throw new IllegalStateException("Ambiguous @ExceptionHandler method mapped for [" +
					exceptionType + "]: {" + oldMethod + ", " + method + "}");
		}
	}

	/**
	 * Whether the contained type has any exception mappings.
	 */
	public boolean hasExceptionMappings() {
		return !this.mappedMethods.isEmpty();
	}

	/**
	 * Find a {@link Method} to handle the given exception.
	 * Use {@link ExceptionDepthComparator} if more than one match is found.
	 * @param exception the exception
	 * @return a Method to handle the exception, or {@code null} if none found
	 */
	@Nullable
	public Method resolveMethod(Exception exception) { // 获取匹配该异常类的方法
		return resolveMethodByThrowable(exception); // 获取匹配该异常类的方法
	}

	/**
	 * Find a {@link Method} to handle the given Throwable.
	 * Use {@link ExceptionDepthComparator} if more than one match is found.
	 * @param exception the exception
	 * @return a Method to handle the exception, or {@code null} if none found
	 * @since 5.0
	 */
	@Nullable
	public Method resolveMethodByThrowable(Throwable exception) { // 从缓存中获取匹配该异常类的方法
		Method method = resolveMethodByExceptionType(exception.getClass()); // 从缓存中获取匹配该异常类的方法
		if (method == null) {
			Throwable cause = exception.getCause(); // 获取不到时，根据cause中的异常类再次匹配
			if (cause != null) {
				method = resolveMethodByExceptionType(cause.getClass()); // 从缓存中获取匹配该异常类的方法
			}
		}
		return method;
	}

	/**
	 * Find a {@link Method} to handle the given exception type. This can be
	 * useful if an {@link Exception} instance is not available (e.g. for tools).
	 * @param exceptionType the exception type
	 * @return a Method to handle the exception, or {@code null} if none found
	 */
	@Nullable
	public Method resolveMethodByExceptionType(Class<? extends Throwable> exceptionType) { // 从缓存中获取匹配该异常类的方法
		Method method = this.exceptionLookupCache.get(exceptionType);
		if (method == null) {
			method = getMappedMethod(exceptionType); // 从mappedMethods容器（一级缓存）中获取匹配该异常类的方法
			this.exceptionLookupCache.put(exceptionType, method); // 放入exceptionLookupCache容器（二级缓存）
		}
		return method;
	}

	/**
	 * Return the {@link Method} mapped to the given exception type, or {@code null} if none.
	 */
	@Nullable
	private Method getMappedMethod(Class<? extends Throwable> exceptionType) { // 从mappedMethods容器（一级缓存）中获取匹配该异常类的方法
		List<Class<? extends Throwable>> matches = new ArrayList<>(); // 定义集合
		for (Class<? extends Throwable> mappedException : this.mappedMethods.keySet()) { // 遍历所有定义的异常
			if (mappedException.isAssignableFrom(exceptionType)) { // 判断传入的异常类是否属于当前遍历的异常类
				matches.add(mappedException);
			}
		}
		if (!matches.isEmpty()) {
			matches.sort(new ExceptionDepthComparator(exceptionType)); // 排序
			return this.mappedMethods.get(matches.get(0)); // 获取第一个元素
		}
		else {
			return null;
		}
	}

}
