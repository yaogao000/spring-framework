/*
 * Copyright 2002-2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.web.servlet.mvc.method.annotation;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.util.Assert;


/**
 * A controller method return value type for asynchronous request processing
 * where one or more objects are written to the response. While
 * {@link org.springframework.web.context.request.async.DeferredResult DeferredResult}
 * is used to produce a single result, a {@code ResponseBodyEmitter} can be used
 * to send multiple objects where each object is written with a compatible
 * {@link org.springframework.http.converter.HttpMessageConverter HttpMessageConverter}.
 *
 * <p>Supported as a return type on its own as well as within a
 * {@link org.springframework.http.ResponseEntity ResponseEntity}.
 *
 * <pre>
 * &#064;RequestMapping(value="/stream", method=RequestMethod.GET)
 * public ResponseBodyEmitter handle() {
 * 	ResponseBodyEmitter emitter = new ResponseBodyEmitter();
 * 	// Pass the emitter to another component...
 * 	return emitter;
 * }
 *
 * // in another thread
 * emitter.send(foo1);
 *
 * // and again
 * emitter.send(foo2);
 *
 * // and done
 * emitter.complete();
 * </pre>
 *
 * <p><strong>Note:</strong> this class is not thread-safe. Callers must ensure
 * that use from multiple threads is synchronized.
 *
 * @author Rossen Stoyanchev
 * @since 4.2
 */
public class ResponseBodyEmitter {

	private Handler handler;

	/* Cache for objects sent before handler is set. */
	private final Map<Object, MediaType> initHandlerCache = new LinkedHashMap<Object, MediaType>(10);

	private volatile boolean complete;

	private Throwable failure;


	/**
	 * Invoked after the response is updated with the status code and headers,
	 * if the ResponseBodyEmitter is wrapped in a ResponseEntity, but before the
	 * response is committed, i.e. before the response body has been written to.
	 * <p>The default implementation is empty.
	 */
	protected void extendResponse(ServerHttpResponse outputMessage) {
	}

	void initialize(Handler handler) throws IOException {
		synchronized (this) {
			this.handler = handler;
			for (Map.Entry<Object, MediaType> entry : this.initHandlerCache.entrySet()) {
				try {
					sendInternal(entry.getKey(), entry.getValue());
				}
				catch (Throwable ex) {
					return;
				}
			}
			if (this.complete) {
				if (this.failure != null) {
					this.handler.completeWithError(this.failure);
				}
				else {
					this.handler.complete();
				}
			}
		}
	}

	/**
	 * Write the given object to the response.
	 * <p>If any exception occurs a dispatch is made back to the app server where
	 * Spring MVC will pass the exception through its exception handling mechanism.
	 * @param object the object to write
	 * @throws IOException raised when an I/O error occurs
	 * @throws java.lang.IllegalStateException wraps any other errors
	 */
	public void send(Object object) throws IOException {
		send(object, null);
	}

	/**
	 * Write the given object to the response also using a MediaType hint.
	 * <p>If any exception occurs a dispatch is made back to the app server where
	 * Spring MVC will pass the exception through its exception handling mechanism.
	 * @param object the object to write
	 * @param mediaType a MediaType hint for selecting an HttpMessageConverter
	 * @throws IOException raised when an I/O error occurs
	 * @throws java.lang.IllegalStateException wraps any other errors
	 */
	public void send(Object object, MediaType mediaType) throws IOException {
		Assert.state(!this.complete, "ResponseBodyEmitter is already set complete.");
		sendInternal(object, mediaType);
	}

	private void sendInternal(Object object, MediaType mediaType) throws IOException {
		if (object == null) {
			return;
		}
		if (handler == null) {
			synchronized (this) {
				if (handler == null) {
					this.initHandlerCache.put(object, mediaType);
					return;
				}
			}
		}
		try {
			this.handler.send(object, mediaType);
		}
		catch(IOException ex){
			this.handler.completeWithError(ex);
			throw ex;
		}
		catch(Throwable ex){
			this.handler.completeWithError(ex);
			throw new IllegalStateException("Failed to send " + object, ex);
		}
	}

	/**
	 * Complete request processing.
	 * <p>A dispatch is made into the app server where Spring MVC completes
	 * asynchronous request processing.
	 */
	public void complete() {
		synchronized (this) {
			this.complete = true;
			if (handler != null) {
				this.handler.complete();
			}
		}
	}

	/**
	 * Complete request processing with an error.
	 * <p>A dispatch is made into the app server where Spring MVC will pass the
	 * exception through its exception handling mechanism.
	 */
	public void completeWithError(Throwable ex) {
		synchronized (this) {
			this.complete = true;
			this.failure = ex;
			if (handler != null) {
				this.handler.completeWithError(ex);
			}
		}
	}


	/**
	 * Handle sent objects and complete request processing.
	 */
	interface Handler {

		void send(Object data, MediaType mediaType) throws IOException;

		void complete();

		void completeWithError(Throwable failure);
	}

}
