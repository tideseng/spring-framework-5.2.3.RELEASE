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

package org.springframework.web.servlet.handler;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;

import kotlin.reflect.KFunction;
import kotlin.reflect.jvm.ReflectJvmMapping;

import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.KotlinDetector;
import org.springframework.core.MethodIntrospector;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Abstract base class for {@link HandlerMapping} implementations that define
 * a mapping between a request and a {@link HandlerMethod}.
 *
 * <p>For each registered handler method, a unique mapping is maintained with
 * subclasses defining the details of the mapping type {@code <T>}.
 *
 * @author Arjen Poutsma
 * @author Rossen Stoyanchev
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @since 3.1
 * @param <T> the mapping for a {@link HandlerMethod} containing the conditions
 * needed to match the handler method to an incoming request.
 */
public abstract class AbstractHandlerMethodMapping<T> extends AbstractHandlerMapping implements InitializingBean {

	/**
	 * Bean name prefix for target beans behind scoped proxies. Used to exclude those
	 * targets from handler method detection, in favor of the corresponding proxies.
	 * <p>We're not checking the autowire-candidate status here, which is how the
	 * proxy target filtering problem is being handled at the autowiring level,
	 * since autowire-candidate may have been turned to {@code false} for other
	 * reasons, while still expecting the bean to be eligible for handler methods.
	 * <p>Originally defined in {@link org.springframework.aop.scope.ScopedProxyUtils}
	 * but duplicated here to avoid a hard dependency on the spring-aop module.
	 */
	private static final String SCOPED_TARGET_NAME_PREFIX = "scopedTarget.";

	private static final HandlerMethod PREFLIGHT_AMBIGUOUS_MATCH = // 默认跨域HandlerMethod
			new HandlerMethod(new EmptyHandler(), ClassUtils.getMethod(EmptyHandler.class, "handle"));

	private static final CorsConfiguration ALLOW_CORS_CONFIG = new CorsConfiguration(); // 默认跨域配置CorsConfiguration

	static { // 通过静态方法设置CorsConfiguration相关属性
		ALLOW_CORS_CONFIG.addAllowedOrigin("*");
		ALLOW_CORS_CONFIG.addAllowedMethod("*");
		ALLOW_CORS_CONFIG.addAllowedHeader("*");
		ALLOW_CORS_CONFIG.setAllowCredentials(true);
	}


	private boolean detectHandlerMethodsInAncestorContexts = false;

	@Nullable
	private HandlerMethodMappingNamingStrategy<T> namingStrategy; // 通常情况下是RequestMappingInfoHandlerMethodMappingNamingStrategy

	private final MappingRegistry mappingRegistry = new MappingRegistry(); // 映射注册器


	/**
	 * Whether to detect handler methods in beans in ancestor ApplicationContexts.
	 * <p>Default is "false": Only beans in the current ApplicationContext are
	 * considered, i.e. only in the context that this HandlerMapping itself
	 * is defined in (typically the current DispatcherServlet's context).
	 * <p>Switch this flag on to detect handler beans in ancestor contexts
	 * (typically the Spring root WebApplicationContext) as well.
	 * @see #getCandidateBeanNames()
	 */
	public void setDetectHandlerMethodsInAncestorContexts(boolean detectHandlerMethodsInAncestorContexts) {
		this.detectHandlerMethodsInAncestorContexts = detectHandlerMethodsInAncestorContexts;
	}

	/**
	 * Configure the naming strategy to use for assigning a default name to every
	 * mapped handler method.
	 * <p>The default naming strategy is based on the capital letters of the
	 * class name followed by "#" and then the method name, e.g. "TC#getFoo"
	 * for a class named TestController with method getFoo.
	 */
	public void setHandlerMethodMappingNamingStrategy(HandlerMethodMappingNamingStrategy<T> namingStrategy) { // 设置HandlerMethodMappingNamingStrategy（通常情况下是RequestMappingInfoHandlerMethodMappingNamingStrategy）
		this.namingStrategy = namingStrategy;
	}

	/**
	 * Return the configured naming strategy or {@code null}.
	 */
	@Nullable
	public HandlerMethodMappingNamingStrategy<T> getNamingStrategy() {
		return this.namingStrategy;
	}

	/**
	 * Return a (read-only) map with all mappings and HandlerMethod's.
	 */
	public Map<T, HandlerMethod> getHandlerMethods() {
		this.mappingRegistry.acquireReadLock();
		try {
			return Collections.unmodifiableMap(this.mappingRegistry.getMappings());
		}
		finally {
			this.mappingRegistry.releaseReadLock();
		}
	}

	/**
	 * Return the handler methods for the given mapping name.
	 * @param mappingName the mapping name
	 * @return a list of matching HandlerMethod's or {@code null}; the returned
	 * list will never be modified and is safe to iterate.
	 * @see #setHandlerMethodMappingNamingStrategy
	 */
	@Nullable
	public List<HandlerMethod> getHandlerMethodsForMappingName(String mappingName) {
		return this.mappingRegistry.getHandlerMethodsByMappingName(mappingName);
	}

	/**
	 * Return the internal mapping registry. Provided for testing purposes.
	 */
	MappingRegistry getMappingRegistry() {
		return this.mappingRegistry;
	}

	/**
	 * Register the given mapping.
	 * <p>This method may be invoked at runtime after initialization has completed.
	 * @param mapping the mapping for the handler method
	 * @param handler the handler
	 * @param method the method
	 */
	public void registerMapping(T mapping, Object handler, Method method) {
		if (logger.isTraceEnabled()) {
			logger.trace("Register \"" + mapping + "\" to " + method.toGenericString());
		}
		this.mappingRegistry.register(mapping, handler, method);
	}

	/**
	 * Un-register the given mapping.
	 * <p>This method may be invoked at runtime after initialization has completed.
	 * @param mapping the mapping to unregister
	 */
	public void unregisterMapping(T mapping) {
		if (logger.isTraceEnabled()) {
			logger.trace("Unregister mapping \"" + mapping + "\"");
		}
		this.mappingRegistry.unregister(mapping);
	}


	// Handler method detection

	/**
	 * Detects handler methods at initialization.
	 * @see #initHandlerMethods
	 */
	@Override
	public void afterPropertiesSet() { // 实现InitializingBean接口来初始化HandlerMethod，并注册到MappingRegistry映射注册器中
		initHandlerMethods(); // 初始化HandlerMethod
	}

	/**
	 * Scan beans in the ApplicationContext, detect and register handler methods.
	 * @see #getCandidateBeanNames()
	 * @see #processCandidateBean
	 * @see #handlerMethodsInitialized
	 */
	protected void initHandlerMethods() { // 初始化HandlerMethod
		for (String beanName : getCandidateBeanNames()) { // 从容器中获取Object类型的beanName
			if (!beanName.startsWith(SCOPED_TARGET_NAME_PREFIX)) { // 不为ScopeProxy代理类才成立，因为代理时方法可能被代理重写了
				processCandidateBean(beanName); // 初始化类上有@Controller注解或@RequestMapping注解的HandlerMethod
			}
		}
		handlerMethodsInitialized(getHandlerMethods());
	}

	/**
	 * Determine the names of candidate beans in the application context.
	 * @since 5.1
	 * @see #setDetectHandlerMethodsInAncestorContexts
	 * @see BeanFactoryUtils#beanNamesForTypeIncludingAncestors
	 */
	protected String[] getCandidateBeanNames() { // 从容器中获取Object类型的beanName
		return (this.detectHandlerMethodsInAncestorContexts ?
				BeanFactoryUtils.beanNamesForTypeIncludingAncestors(obtainApplicationContext(), Object.class) :
				obtainApplicationContext().getBeanNamesForType(Object.class));
	}

	/**
	 * Determine the type of the specified candidate bean and call
	 * {@link #detectHandlerMethods} if identified as a handler type.
	 * <p>This implementation avoids bean creation through checking
	 * {@link org.springframework.beans.factory.BeanFactory#getType}
	 * and calling {@link #detectHandlerMethods} with the bean name.
	 * @param beanName the name of the candidate bean
	 * @since 5.1
	 * @see #isHandler
	 * @see #detectHandlerMethods
	 */
	protected void processCandidateBean(String beanName) {// 初始化类上有@Controller注解或@RequestMapping注解的HandlerMethod
		Class<?> beanType = null;
		try {
			beanType = obtainApplicationContext().getType(beanName); // 获取beanName的Class类型
		}
		catch (Throwable ex) {
			// An unresolvable bean type, probably from a lazy bean - let's ignore it.
			if (logger.isTraceEnabled()) {
				logger.trace("Could not resolve type for bean '" + beanName + "'", ex);
			}
		}
		if (beanType != null && isHandler(beanType)) { // 判断Class类上是否有@Controller注解或@RequestMapping注解
			detectHandlerMethods(beanName); // 初始化HandlerMethod
		}
	}

	/**
	 * Look for handler methods in the specified handler bean.
	 * @param handler either a bean name or an actual handler instance
	 * @see #getMappingForMethod
	 */
	protected void detectHandlerMethods(Object handler) { // 初始化HandlerMethod（这里的入参可能是Controller名称，还可能是Controller实例）
		Class<?> handlerType = (handler instanceof String ? // 获取Handler类的Class
				obtainApplicationContext().getType((String) handler) : handler.getClass());

		if (handlerType != null) {
			Class<?> userType = ClassUtils.getUserClass(handlerType); // 获取用户定义的类型（针对动态代理类型）
			Map<Method, T> methods = MethodIntrospector.selectMethods(userType, // 建立@RequestMapping注解修饰的Method对象与RequestMappingInfo的映射关系
					(MethodIntrospector.MetadataLookup<T>) method -> { // 使用函数式接口注入
						try {
							return getMappingForMethod(method, userType); // 对有@RequestMapping注解的方法构建RequestMappingInfo（抽象方法，需要子类实现）
						}
						catch (Throwable ex) {
							throw new IllegalStateException("Invalid mapping on handler class [" +
									userType.getName() + "]: " + method, ex);
						}
					});
			if (logger.isTraceEnabled()) {
				logger.trace(formatMappings(userType, methods));
			}
			methods.forEach((method, mapping) -> { // 遍历Map，能遍历则说明当前类有@RequestMapping注解的方法
				Method invocableMethod = AopUtils.selectInvocableMethod(method, userType);
				registerHandlerMethod(handler, invocableMethod, mapping); // 注册HandlerMethod到MappingRegistry，并建立url与HandlerMethod方法的映射关系（并不是直接建立），RequestMappingHandlerMapping映射关系注册入口、也是解析@CrossOrigin的入口
			});
		}
	}

	private String formatMappings(Class<?> userType, Map<Method, T> methods) {
		String formattedType = Arrays.stream(ClassUtils.getPackageName(userType).split("\\."))
				.map(p -> p.substring(0, 1))
				.collect(Collectors.joining(".", "", "." + userType.getSimpleName()));
		Function<Method, String> methodFormatter = method -> Arrays.stream(method.getParameterTypes())
				.map(Class::getSimpleName)
				.collect(Collectors.joining(",", "(", ")"));
		return methods.entrySet().stream()
				.map(e -> {
					Method method = e.getKey();
					return e.getValue() + ": " + method.getName() + methodFormatter.apply(method);
				})
				.collect(Collectors.joining("\n\t", "\n\t" + formattedType + ":" + "\n\t", ""));
	}

	/**
	 * Register a handler method and its unique mapping. Invoked at startup for
	 * each detected handler method.
	 * @param handler the bean name of the handler or the handler instance
	 * @param method the method to register
	 * @param mapping the mapping conditions associated with the handler method
	 * @throws IllegalStateException if another method was already registered
	 * under the same mapping
	 */
	protected void registerHandlerMethod(Object handler, Method method, T mapping) { // 注册HandlerMethod到MappingRegistry，建立映射关系
		this.mappingRegistry.register(mapping, handler, method); // 注册HandlerMethod到MappingRegistry，建立映射关系
	}

	/**
	 * Create the HandlerMethod instance.
	 * @param handler either a bean name or an actual handler instance
	 * @param method the target method
	 * @return the created HandlerMethod
	 */
	protected HandlerMethod createHandlerMethod(Object handler, Method method) { // 构建HandlerMethod，注入Controller类名、Method对象
		if (handler instanceof String) {
			return new HandlerMethod((String) handler, // 创建HandlerMethod
					obtainApplicationContext().getAutowireCapableBeanFactory(), method);
		}
		return new HandlerMethod(handler, method); // 创建HandlerMethod
	}

	/**
	 * Extract and return the CORS configuration for the mapping.
	 */
	@Nullable
	protected CorsConfiguration initCorsConfiguration(Object handler, Method method, T mapping) {
		return null;
	}

	/**
	 * Invoked after all handler methods have been detected.
	 * @param handlerMethods a read-only map with handler methods and mappings.
	 */
	protected void handlerMethodsInitialized(Map<T, HandlerMethod> handlerMethods) {
		// Total includes detected mappings + explicit registrations via registerMapping
		int total = handlerMethods.size();
		if ((logger.isTraceEnabled() && total == 0) || (logger.isDebugEnabled() && total > 0) ) {
			logger.debug(total + " mappings in " + formatMappingName());
		}
	}


	// Handler method lookup

	/**
	 * Look up a handler method for the given request.
	 */
	@Override
	protected HandlerMethod getHandlerInternal(HttpServletRequest request) throws Exception { // 根据请求url获取对应的HandlerMethod
		String lookupPath = getUrlPathHelper().getLookupPathForRequest(request); // 获取请求url
		request.setAttribute(LOOKUP_PATH, lookupPath); // 将请求url设置到Request请求对象中
		this.mappingRegistry.acquireReadLock(); // 获取读锁
		try {
			HandlerMethod handlerMethod = lookupHandlerMethod(lookupPath, request); // 根据请求url获取对应的HandlerMethod
			return (handlerMethod != null ? handlerMethod.createWithResolvedBean() : null); // 将HandlerMethod中的Controller类名bean，转为Controller实例
		}
		finally {
			this.mappingRegistry.releaseReadLock(); // 释放读锁
		}
	}

	/**
	 * Look up the best-matching handler method for the current request.
	 * If multiple matches are found, the best match is selected.
	 * @param lookupPath mapping lookup path within the current servlet mapping
	 * @param request the current request
	 * @return the best-matching handler method, or {@code null} if no match
	 * @see #handleMatch(Object, String, HttpServletRequest)
	 * @see #handleNoMatch(Set, String, HttpServletRequest)
	 */
	@Nullable
	protected HandlerMethod lookupHandlerMethod(String lookupPath, HttpServletRequest request) throws Exception { // 根据请求url获取对应的HandlerMethod
		List<Match> matches = new ArrayList<>(); // 匹配到的Match集合（注入了RequestMappingInfo、HandlerMethod）
		List<T> directPathMatches = this.mappingRegistry.getMappingsByUrl(lookupPath); // 根据请求url获取缓存的RequestMappingInfo列表
		if (directPathMatches != null) {
			addMatchingMappings(directPathMatches, matches, request); // 尝试从缓存中的RequestMappingInfo列表进行匹配
		}
		if (matches.isEmpty()) { // 如果没有匹配到，则尝试中所有的RequestMappingInfo列表进行匹配
			// No choice but to go through all mappings...
			addMatchingMappings(this.mappingRegistry.getMappings().keySet(), matches, request);
		}

		if (!matches.isEmpty()) { // 匹配到的Match集合不为空时
			Comparator<Match> comparator = new MatchComparator(getMappingComparator(request)); // Match比较器
			matches.sort(comparator); // 排序
			Match bestMatch = matches.get(0); // 获取集合的第一个元素作为最优匹配
			if (matches.size() > 1) { // 当匹配到多个时，判断前两个元素的优先级是否相同，如果是则抛出IllegalStateException异常
				if (logger.isTraceEnabled()) {
					logger.trace(matches.size() + " matching mappings: " + matches);
				}
				if (CorsUtils.isPreFlightRequest(request)) {
					return PREFLIGHT_AMBIGUOUS_MATCH;
				}
				Match secondBestMatch = matches.get(1);
				if (comparator.compare(bestMatch, secondBestMatch) == 0) {
					Method m1 = bestMatch.handlerMethod.getMethod();
					Method m2 = secondBestMatch.handlerMethod.getMethod();
					String uri = request.getRequestURI();
					throw new IllegalStateException(
							"Ambiguous handler methods mapped for '" + uri + "': {" + m1 + ", " + m2 + "}");
				}
			}
			request.setAttribute(BEST_MATCHING_HANDLER_ATTRIBUTE, bestMatch.handlerMethod); // 将HandlerMethod设置到Request属性中
			handleMatch(bestMatch.mapping, lookupPath, request);
			return bestMatch.handlerMethod; // 返回匹配的HandlerMethod
		}
		else {
			return handleNoMatch(this.mappingRegistry.getMappings().keySet(), lookupPath, request);
		}
	}

	private void addMatchingMappings(Collection<T> mappings, List<Match> matches, HttpServletRequest request) { // 添加匹配到的Match
		for (T mapping : mappings) {
			T match = getMatchingMapping(mapping, request); // 根据Request请求进行相关匹配，匹配成功则构建RequestMappingInfo
			if (match != null) {
				matches.add(new Match(match, this.mappingRegistry.getMappings().get(mapping))); // 创建Match对象，并注入RequestMappingInfo、HandlerMethod
			}
		}
	}

	/**
	 * Invoked when a matching mapping is found.
	 * @param mapping the matching mapping
	 * @param lookupPath mapping lookup path within the current servlet mapping
	 * @param request the current request
	 */
	protected void handleMatch(T mapping, String lookupPath, HttpServletRequest request) {
		request.setAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE, lookupPath);
	}

	/**
	 * Invoked when no matching mapping is not found.
	 * @param mappings all registered mappings
	 * @param lookupPath mapping lookup path within the current servlet mapping
	 * @param request the current request
	 * @throws ServletException in case of errors
	 */
	@Nullable
	protected HandlerMethod handleNoMatch(Set<T> mappings, String lookupPath, HttpServletRequest request)
			throws Exception {

		return null;
	}

	@Override
	protected boolean hasCorsConfigurationSource(Object handler) {
		return super.hasCorsConfigurationSource(handler) ||
				(handler instanceof HandlerMethod && this.mappingRegistry.getCorsConfiguration((HandlerMethod) handler) != null);
	}

	@Override
	protected CorsConfiguration getCorsConfiguration(Object handler, HttpServletRequest request) { // 获取@CrossOrigin跨域配置
		CorsConfiguration corsConfig = super.getCorsConfiguration(handler, request); // 调用父类获取跨域配置（通常情况下返回null）
		if (handler instanceof HandlerMethod) {
			HandlerMethod handlerMethod = (HandlerMethod) handler;
			if (handlerMethod.equals(PREFLIGHT_AMBIGUOUS_MATCH)) { // 当为默认跨域HandlerMethod时，返回默认跨域配置
				return AbstractHandlerMethodMapping.ALLOW_CORS_CONFIG;
			}
			else {
				CorsConfiguration corsConfigFromMethod = this.mappingRegistry.getCorsConfiguration(handlerMethod); // 从映射容器中根据HandlerMethod获取@CrossOrigin注解生成的CorsConfiguration
				corsConfig = (corsConfig != null ? corsConfig.combine(corsConfigFromMethod) : corsConfigFromMethod);
			}
		}
		return corsConfig;
	}


	// Abstract template methods

	/**
	 * Whether the given type is a handler with handler methods.
	 * @param beanType the type of the bean being checked
	 * @return "true" if this a handler type, "false" otherwise.
	 */
	protected abstract boolean isHandler(Class<?> beanType);

	/**
	 * Provide the mapping for a handler method. A method for which no
	 * mapping can be provided is not a handler method.
	 * @param method the method to provide a mapping for
	 * @param handlerType the handler type, possibly a sub-type of the method's
	 * declaring class
	 * @return the mapping, or {@code null} if the method is not mapped
	 */
	@Nullable
	protected abstract T getMappingForMethod(Method method, Class<?> handlerType);

	/**
	 * Extract and return the URL paths contained in the supplied mapping.
	 */
	protected abstract Set<String> getMappingPathPatterns(T mapping);

	/**
	 * Check if a mapping matches the current request and return a (potentially
	 * new) mapping with conditions relevant to the current request.
	 * @param mapping the mapping to get a match for
	 * @param request the current HTTP servlet request
	 * @return the match, or {@code null} if the mapping doesn't match
	 */
	@Nullable
	protected abstract T getMatchingMapping(T mapping, HttpServletRequest request);

	/**
	 * Return a comparator for sorting matching mappings.
	 * The returned comparator should sort 'better' matches higher.
	 * @param request the current request
	 * @return the comparator (never {@code null})
	 */
	protected abstract Comparator<T> getMappingComparator(HttpServletRequest request);


	/**
	 * A registry that maintains all mappings to handler methods, exposing methods
	 * to perform lookups and providing concurrent access.
	 * <p>Package-private for testing purposes.
	 */
	class MappingRegistry { // 映射注册器

		private final Map<T, MappingRegistration<T>> registry = new HashMap<>(); // RequestMappingInfo与MappingRegistration的映射容器

		private final Map<T, HandlerMethod> mappingLookup = new LinkedHashMap<>(); // RequestMappingInfo与HandlerMethod的映射容器

		private final MultiValueMap<String, T> urlLookup = new LinkedMultiValueMap<>(); // url与RequestMappingInfo的映射容器

		private final Map<String, List<HandlerMethod>> nameLookup = new ConcurrentHashMap<>(); // name与HandlerMethod列表的映射容器

		private final Map<HandlerMethod, CorsConfiguration> corsLookup = new ConcurrentHashMap<>(); // HandlerMethod与CorsConfiguration的映射容器

		private final ReentrantReadWriteLock readWriteLock = new ReentrantReadWriteLock(); // 读写锁

		/**
		 * Return all mappings and handler methods. Not thread-safe.
		 * @see #acquireReadLock()
		 */
		public Map<T, HandlerMethod> getMappings() {
			return this.mappingLookup;
		}

		/**
		 * Return matches for the given URL path. Not thread-safe.
		 * @see #acquireReadLock()
		 */
		@Nullable
		public List<T> getMappingsByUrl(String urlPath) { // 根据url获取RequestMappingInfo列表
			return this.urlLookup.get(urlPath);
		}

		/**
		 * Return handler methods by mapping name. Thread-safe for concurrent use.
		 */
		public List<HandlerMethod> getHandlerMethodsByMappingName(String mappingName) {
			return this.nameLookup.get(mappingName);
		}

		/**
		 * Return CORS configuration. Thread-safe for concurrent use.
		 */
		@Nullable
		public CorsConfiguration getCorsConfiguration(HandlerMethod handlerMethod) {
			HandlerMethod original = handlerMethod.getResolvedFromHandlerMethod();
			return this.corsLookup.get(original != null ? original : handlerMethod);
		}

		/**
		 * Acquire the read lock when using getMappings and getMappingsByUrl.
		 */
		public void acquireReadLock() {
			this.readWriteLock.readLock().lock();
		}

		/**
		 * Release the read lock after using getMappings and getMappingsByUrl.
		 */
		public void releaseReadLock() {
			this.readWriteLock.readLock().unlock();
		}

		public void register(T mapping, Object handler, Method method) { // 注册HandlerMethod，建立映射关系（url与RequestMappingInfo的映射关系、RequestMappingInfo与HandlerMethod的映射关系）
			// Assert that the handler method is not a suspending one.
			if (KotlinDetector.isKotlinType(method.getDeclaringClass()) && KotlinDelegate.isSuspend(method)) {
				throw new IllegalStateException("Unsupported suspending handler method detected: " + method);
			}
			this.readWriteLock.writeLock().lock(); // 获取写锁
			try {
				HandlerMethod handlerMethod = createHandlerMethod(handler, method); // 构建HandlerMethod，注入Controller类名、Method对象
				validateMethodMapping(handlerMethod, mapping); // 校验是否唯一
				this.mappingLookup.put(mapping, handlerMethod); // 1.建立RequestMappingInfo与HandlerMethod的映射关系

				List<String> directUrls = getDirectUrls(mapping); // 获取明确的路径列表
				for (String url : directUrls) {
					this.urlLookup.add(url, mapping); // 2.建立url与RequestMappingInfo的映射关系
				}

				String name = null;
				if (getNamingStrategy() != null) { // 默认为RequestMappingInfoHandlerMethodMappingNamingStrategy
					name = getNamingStrategy().getName(handlerMethod, mapping); // 获取名称
					addMappingName(name, handlerMethod); // 3.建立name与HandlerMethod列表的映射关系
				}

				CorsConfiguration corsConfig = initCorsConfiguration(handler, method, mapping); // 初始化方法上或类上的CorsConfiguration（判断方法上或类上是否有@CrossOrigin注解，将其封装成CorsConfiguration）
				if (corsConfig != null) {
					this.corsLookup.put(handlerMethod, corsConfig); // 4.建立HandlerMethod与CorsConfiguration的映射关系
				}

				this.registry.put(mapping, new MappingRegistration<>(mapping, handlerMethod, directUrls, name)); // 5.建立RequestMappingInfo与MappingRegistration的映射关系
			}
			finally {
				this.readWriteLock.writeLock().unlock(); // 释放写锁
			}
		}

		private void validateMethodMapping(HandlerMethod handlerMethod, T mapping) { // 校验是否唯一
			// Assert that the supplied mapping is unique.
			HandlerMethod existingHandlerMethod = this.mappingLookup.get(mapping); // 如果RequestMappingInfo对应的HandlerMethod已存在，且和传入的相同，则抛出异常
			if (existingHandlerMethod != null && !existingHandlerMethod.equals(handlerMethod)) {
				throw new IllegalStateException(
						"Ambiguous mapping. Cannot map '" + handlerMethod.getBean() + "' method \n" +
						handlerMethod + "\nto " + mapping + ": There is already '" +
						existingHandlerMethod.getBean() + "' bean method\n" + existingHandlerMethod + " mapped.");
			}
		}

		private List<String> getDirectUrls(T mapping) { // 获取明确的路径列表
			List<String> urls = new ArrayList<>(1);
			for (String path : getMappingPathPatterns(mapping)) {
				if (!getPathMatcher().isPattern(path)) { // 只有当路径是明确时，才会添加到列表中
					urls.add(path);
				}
			}
			return urls;
		}

		private void addMappingName(String name, HandlerMethod handlerMethod) { // 添加name与HandlerMethod列表的映射关系
			List<HandlerMethod> oldList = this.nameLookup.get(name);
			if (oldList == null) {
				oldList = Collections.emptyList();
			}

			for (HandlerMethod current : oldList) {
				if (handlerMethod.equals(current)) {
					return;
				}
			}

			List<HandlerMethod> newList = new ArrayList<>(oldList.size() + 1);
			newList.addAll(oldList);
			newList.add(handlerMethod);
			this.nameLookup.put(name, newList); // 添加name与HandlerMethod列表的映射关系
		}

		public void unregister(T mapping) {
			this.readWriteLock.writeLock().lock();
			try {
				MappingRegistration<T> definition = this.registry.remove(mapping);
				if (definition == null) {
					return;
				}

				this.mappingLookup.remove(definition.getMapping());

				for (String url : definition.getDirectUrls()) {
					List<T> list = this.urlLookup.get(url);
					if (list != null) {
						list.remove(definition.getMapping());
						if (list.isEmpty()) {
							this.urlLookup.remove(url);
						}
					}
				}

				removeMappingName(definition);

				this.corsLookup.remove(definition.getHandlerMethod());
			}
			finally {
				this.readWriteLock.writeLock().unlock();
			}
		}

		private void removeMappingName(MappingRegistration<T> definition) {
			String name = definition.getMappingName();
			if (name == null) {
				return;
			}
			HandlerMethod handlerMethod = definition.getHandlerMethod();
			List<HandlerMethod> oldList = this.nameLookup.get(name);
			if (oldList == null) {
				return;
			}
			if (oldList.size() <= 1) {
				this.nameLookup.remove(name);
				return;
			}
			List<HandlerMethod> newList = new ArrayList<>(oldList.size() - 1);
			for (HandlerMethod current : oldList) {
				if (!current.equals(handlerMethod)) {
					newList.add(current);
				}
			}
			this.nameLookup.put(name, newList);
		}
	}


	private static class MappingRegistration<T> {

		private final T mapping; // RequestMappingInfo

		private final HandlerMethod handlerMethod;

		private final List<String> directUrls;

		@Nullable
		private final String mappingName; // HandlerMethod对应的mappingName

		public MappingRegistration(T mapping, HandlerMethod handlerMethod, // 实例化MappingRegistration
				@Nullable List<String> directUrls, @Nullable String mappingName) {

			Assert.notNull(mapping, "Mapping must not be null");
			Assert.notNull(handlerMethod, "HandlerMethod must not be null");
			this.mapping = mapping;
			this.handlerMethod = handlerMethod;
			this.directUrls = (directUrls != null ? directUrls : Collections.emptyList());
			this.mappingName = mappingName;
		}

		public T getMapping() {
			return this.mapping;
		}

		public HandlerMethod getHandlerMethod() {
			return this.handlerMethod;
		}

		public List<String> getDirectUrls() {
			return this.directUrls;
		}

		@Nullable
		public String getMappingName() {
			return this.mappingName;
		}
	}


	/**
	 * A thin wrapper around a matched HandlerMethod and its mapping, for the purpose of
	 * comparing the best match with a comparator in the context of the current request.
	 */
	private class Match {

		private final T mapping;

		private final HandlerMethod handlerMethod;

		public Match(T mapping, HandlerMethod handlerMethod) { // 初始化Match对象，并注入RequestMappingInfo、HandlerMethod
			this.mapping = mapping;
			this.handlerMethod = handlerMethod;
		}

		@Override
		public String toString() {
			return this.mapping.toString();
		}
	}


	private class MatchComparator implements Comparator<Match> { // Match比较器

		private final Comparator<T> comparator;

		public MatchComparator(Comparator<T> comparator) { // 创建Match比较器，传入一个比较器
			this.comparator = comparator;
		}

		@Override
		public int compare(Match match1, Match match2) { // 比较Match
			return this.comparator.compare(match1.mapping, match2.mapping);
		}
	}


	private static class EmptyHandler {

		@SuppressWarnings("unused")
		public void handle() {
			throw new UnsupportedOperationException("Not implemented");
		}
	}

	/**
	 * Inner class to avoid a hard dependency on Kotlin at runtime.
	 */
	private static class KotlinDelegate {

		static private boolean isSuspend(Method method) {
			KFunction<?> function = ReflectJvmMapping.getKotlinFunction(method);
			return function != null && function.isSuspend();
		}
	}

}
