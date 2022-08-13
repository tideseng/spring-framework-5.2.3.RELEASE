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

package org.springframework.http.client;

import java.io.IOException;
import java.net.URI;
import java.util.Iterator;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.StreamingHttpOutputMessage;
import org.springframework.util.Assert;
import org.springframework.util.StreamUtils;

/**
 * Wrapper for a {@link ClientHttpRequest} that has support for {@link ClientHttpRequestInterceptor
 * ClientHttpRequestInterceptors}.
 *
 * @author Arjen Poutsma
 * @since 3.1
 */
class InterceptingClientHttpRequest extends AbstractBufferingClientHttpRequest { // 客户端Http请求拦截类

	private final ClientHttpRequestFactory requestFactory; // 客户端Http请求工厂SimpleClientHttpRequestFactory

	private final List<ClientHttpRequestInterceptor> interceptors; // Http请求拦截器链

	private HttpMethod method;

	private URI uri;


	protected InterceptingClientHttpRequest(ClientHttpRequestFactory requestFactory, // 初始化InterceptingClientHttpRequest（在InterceptingClientHttpRequestFactory中进行调用创建）
			List<ClientHttpRequestInterceptor> interceptors, URI uri, HttpMethod method) {

		this.requestFactory = requestFactory; // 客户端Http请求工厂SimpleClientHttpRequestFactory
		this.interceptors = interceptors; // 将RestTemplate中的拦截器链进行注入
		this.method = method;
		this.uri = uri;
	}


	@Override
	public HttpMethod getMethod() {
		return this.method;
	}

	@Override
	public String getMethodValue() {
		return this.method.name();
	}

	@Override
	public URI getURI() {
		return this.uri;
	}

	@Override
	protected final ClientHttpResponse executeInternal(HttpHeaders headers, byte[] bufferedOutput) throws IOException {
		InterceptingRequestExecution requestExecution = new InterceptingRequestExecution(); // 创建InterceptingRequestExecution
		return requestExecution.execute(this, bufferedOutput); // 真正的execute执行方法
	}


	private class InterceptingRequestExecution implements ClientHttpRequestExecution { // 创建InterceptingRequestExecution

		private final Iterator<ClientHttpRequestInterceptor> iterator; // Http请求拦截器迭代器

		public InterceptingRequestExecution() { // 初始化InterceptingRequestExecution
			this.iterator = interceptors.iterator(); // 在构造方法中将外部类中的拦截器链进行注入
		}

		@Override
		public ClientHttpResponse execute(HttpRequest request, byte[] body) throws IOException { // 真正的execute执行方法
			if (this.iterator.hasNext()) { // 判断是否还有拦截器，有拦截器时先执行拦截器的intercept方法
				ClientHttpRequestInterceptor nextInterceptor = this.iterator.next(); // 获取当前拦截器
				return nextInterceptor.intercept(request, body, this); // 调用ClientHttpRequestInterceptor拦截器实现类的intercept方法（是LoadBalancerInterceptor拦截方法的入口）
			}
			else { // 当拦截器执行完毕之后会回到该execute方法中，走else逻辑，此时delegate类型是SimpleBufferingClientHttpRequest
				HttpMethod method = request.getMethod();
				Assert.state(method != null, "No standard HTTP method");
				ClientHttpRequest delegate = requestFactory.createRequest(request.getURI(), method); // 重构url，获取真实的uri地址，再通过请求工厂SimpleClientHttpRequestFactory创建请求
				request.getHeaders().forEach((key, value) -> delegate.getHeaders().addAll(key, value));
				if (body.length > 0) {
					if (delegate instanceof StreamingHttpOutputMessage) {
						StreamingHttpOutputMessage streamingOutputMessage = (StreamingHttpOutputMessage) delegate;
						streamingOutputMessage.setBody(outputStream -> StreamUtils.copy(body, outputStream));
					}
					else {
						StreamUtils.copy(body, delegate.getBody());
					}
				}
				return delegate.execute(); // 调用SimpleBufferingClientHttpRequest执行请求，内部是通过HttpURLConnection进行调用
			}
		}
	}

}
