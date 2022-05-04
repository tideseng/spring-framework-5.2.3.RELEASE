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

package org.springframework.http.client.support;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.logging.Log;

import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.http.HttpLogging;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestInitializer;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.Assert;

/**
 * Base class for {@link org.springframework.web.client.RestTemplate}
 * and other HTTP accessing gateway helpers, defining common properties
 * such as the {@link ClientHttpRequestFactory} to operate on.
 *
 * <p>Not intended to be used directly.
 *
 * <p>See {@link org.springframework.web.client.RestTemplate} for an entry point.
 *
 * @author Arjen Poutsma
 * @author Juergen Hoeller
 * @author Phillip Webb
 * @since 3.0
 * @see ClientHttpRequestFactory
 * @see org.springframework.web.client.RestTemplate
 */
public abstract class HttpAccessor { // Http访问器、抽象类，内部保存了请求工厂类

	/** Logger available to subclasses. */
	protected final Log logger = HttpLogging.forLogName(getClass());
	// 请求工厂类，通过调用createRequest方法生产ClientHttpRequest（RestTemplate最终会将请求构造成ClientHttpRequest，由ClientHttpRequest负责与服务端进行交互）
	private ClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();

	private final List<ClientHttpRequestInitializer> clientHttpRequestInitializers = new ArrayList<>();


	/**
	 * Set the request factory that this accessor uses for obtaining client request handles.
	 * <p>The default is a {@link SimpleClientHttpRequestFactory} based on the JDK's own
	 * HTTP libraries ({@link java.net.HttpURLConnection}).
	 * <p><b>Note that the standard JDK HTTP library does not support the HTTP PATCH method.
	 * Configure the Apache HttpComponents or OkHttp request factory to enable PATCH.</b>
	 * @see #createRequest(URI, HttpMethod)
	 * @see SimpleClientHttpRequestFactory
	 * @see org.springframework.http.client.HttpComponentsAsyncClientHttpRequestFactory
	 * @see org.springframework.http.client.OkHttp3ClientHttpRequestFactory
	 */
	public void setRequestFactory(ClientHttpRequestFactory requestFactory) {
		Assert.notNull(requestFactory, "ClientHttpRequestFactory must not be null");
		this.requestFactory = requestFactory;
	}

	/**
	 * Return the request factory that this accessor uses for obtaining client request handles.
	 */
	public ClientHttpRequestFactory getRequestFactory() { // 获取请求工厂类，默认为SimpleClientHttpRequestFactory（但被子类InterceptingHttpAccessor重写）
		return this.requestFactory;
	}


	/**
	 * Set the request initializers that this accessor should use.
	 * <p>The initializers will get immediately sorted according to their
	 * {@linkplain AnnotationAwareOrderComparator#sort(List) order}.
	 * @since 5.2
	 */
	public void setClientHttpRequestInitializers(
			List<ClientHttpRequestInitializer> clientHttpRequestInitializers) {

		if (this.clientHttpRequestInitializers != clientHttpRequestInitializers) {
			this.clientHttpRequestInitializers.clear();
			this.clientHttpRequestInitializers.addAll(clientHttpRequestInitializers);
			AnnotationAwareOrderComparator.sort(this.clientHttpRequestInitializers);
		}
	}

	/**
	 * Get the request initializers that this accessor uses.
	 * <p>The returned {@link List} is active and may be modified. Note,
	 * however, that the initializers will not be resorted according to their
	 * {@linkplain AnnotationAwareOrderComparator#sort(List) order} before the
	 * {@link ClientHttpRequest} is initialized.
	 * @since 5.2
	 * @see #setClientHttpRequestInitializers(List)
	 */
	public List<ClientHttpRequestInitializer> getClientHttpRequestInitializers() {
		return this.clientHttpRequestInitializers;
	}

	/**
	 * Create a new {@link ClientHttpRequest} via this template's {@link ClientHttpRequestFactory}.
	 * @param url the URL to connect to
	 * @param method the HTTP method to execute (GET, POST, etc)
	 * @return the created request
	 * @throws IOException in case of I/O errors
	 * @see #getRequestFactory()
	 * @see ClientHttpRequestFactory#createRequest(URI, HttpMethod)
	 */
	protected ClientHttpRequest createRequest(URI url, HttpMethod method) throws IOException {
		ClientHttpRequest request = getRequestFactory().createRequest(url, method); // 调用子类获取请求工厂InterceptingClientHttpRequestFactory，再调用父类createRequest方法生成InterceptingClientHttpRequest
		initialize(request);
		if (logger.isDebugEnabled()) {
			logger.debug("HTTP " + method.name() + " " + url);
		}
		return request;
	}

	private void initialize(ClientHttpRequest request) {
		this.clientHttpRequestInitializers.forEach(initializer -> initializer.initialize(request));
	}

}
