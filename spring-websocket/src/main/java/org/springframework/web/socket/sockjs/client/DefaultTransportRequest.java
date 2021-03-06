/*
 * Copyright 2002-2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.web.socket.sockjs.client;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.util.Assert;
import org.springframework.util.concurrent.ListenableFutureCallback;
import org.springframework.util.concurrent.SettableListenableFuture;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.sockjs.SockJsTransportFailureException;
import org.springframework.web.socket.sockjs.frame.SockJsMessageCodec;
import org.springframework.web.socket.sockjs.transport.TransportType;

import java.net.URI;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A default implementation of
 * {@link org.springframework.web.socket.sockjs.client.TransportRequest
 * TransportRequest}.
 *
 * @author Rossen Stoyanchev
 * @since 4.1
 */
class DefaultTransportRequest implements TransportRequest {

	private static Log logger = LogFactory.getLog(DefaultTransportRequest.class);


	private final SockJsUrlInfo sockJsUrlInfo;

	private final HttpHeaders handshakeHeaders;

	private final Transport transport;

	private final TransportType serverTransportType;

	private SockJsMessageCodec codec;

	private Principal user;

	private long timeoutValue;

	private TaskScheduler timeoutScheduler;

	private final List<Runnable> timeoutTasks = new ArrayList<Runnable>();

	private DefaultTransportRequest fallbackRequest;


	public DefaultTransportRequest(SockJsUrlInfo sockJsUrlInfo, HttpHeaders handshakeHeaders,
			Transport transport, TransportType serverTransportType, SockJsMessageCodec codec) {

		Assert.notNull(sockJsUrlInfo, "'sockJsUrlInfo' is required");
		Assert.notNull(transport, "'transport' is required");
		Assert.notNull(serverTransportType, "'transportType' is required");
		Assert.notNull(codec, "'codec' is required");
		this.sockJsUrlInfo = sockJsUrlInfo;
		this.handshakeHeaders = (handshakeHeaders != null ? handshakeHeaders : new HttpHeaders());
		this.transport = transport;
		this.serverTransportType = serverTransportType;
		this.codec = codec;
	}


	@Override
	public SockJsUrlInfo getSockJsUrlInfo() {
		return this.sockJsUrlInfo;
	}

	@Override
	public HttpHeaders getHandshakeHeaders() {
		return this.handshakeHeaders;
	}

	@Override
	public URI getTransportUrl() {
		return this.sockJsUrlInfo.getTransportUrl(this.serverTransportType);
	}

	public void setUser(Principal user) {
		this.user = user;
	}

	@Override
	public Principal getUser() {
		return this.user;
	}

	@Override
	public SockJsMessageCodec getMessageCodec() {
		return this.codec;
	}

	public void setTimeoutValue(long timeoutValue) {
		this.timeoutValue = timeoutValue;
	}

	public void setTimeoutScheduler(TaskScheduler scheduler) {
		this.timeoutScheduler = scheduler;
	}

	@Override
	public void addTimeoutTask(Runnable runnable) {
		this.timeoutTasks.add(runnable);
	}

	public void setFallbackRequest(DefaultTransportRequest fallbackRequest) {
		this.fallbackRequest = fallbackRequest;
	}


	public void connect(WebSocketHandler handler, SettableListenableFuture<WebSocketSession> future) {
		if (logger.isDebugEnabled()) {
			logger.debug("Starting " + this);
		}
		ConnectCallback connectCallback = new ConnectCallback(handler, future);
		scheduleConnectTimeoutTask(connectCallback);
		this.transport.connect(this, handler).addCallback(connectCallback);
	}


	private void scheduleConnectTimeoutTask(ConnectCallback connectHandler) {
		if (this.timeoutScheduler != null) {
			if (logger.isDebugEnabled()) {
				logger.debug("Scheduling connect to time out after " + this.timeoutValue + " milliseconds");
			}
			Date timeoutDate = new Date(System.currentTimeMillis() + this.timeoutValue);
			this.timeoutScheduler.schedule(connectHandler, timeoutDate);
		}
		else if (logger.isDebugEnabled()) {
			logger.debug("Connect timeout task not scheduled. Is SockJsClient configured with a TaskScheduler?");
		}
	}


	@Override
	public String toString() {
		return "TransportRequest[url=" + getTransportUrl() + "]";
	}


	/**
	 * Updates the given (global) future based success or failure to connect for
	 * the entire SockJS request regardless of which transport actually managed
	 * to connect. Also implements {@code Runnable} to handle a scheduled timeout
	 * callback.
	 */
	private class ConnectCallback implements ListenableFutureCallback<WebSocketSession>, Runnable {

		private final WebSocketHandler handler;

		private final SettableListenableFuture<WebSocketSession> future;

		private final AtomicBoolean handled = new AtomicBoolean(false);


		public ConnectCallback(WebSocketHandler handler, SettableListenableFuture<WebSocketSession> future) {
			this.handler = handler;
			this.future = future;
		}


		@Override
		public void onSuccess(WebSocketSession session) {
			if (this.handled.compareAndSet(false, true)) {
				this.future.set(session);
			}
			else {
				logger.error("Connect success/failure already handled for " + DefaultTransportRequest.this);
			}
		}

		@Override
		public void onFailure(Throwable failure) {
			handleFailure(failure, false);
		}

		@Override
		public void run() {
			handleFailure(null, true);
		}

		private void handleFailure(Throwable failure, boolean isTimeoutFailure) {
			if (this.handled.compareAndSet(false, true)) {
				if (isTimeoutFailure) {
					String message = "Connect timed out for " + DefaultTransportRequest.this;
					logger.error(message);
					failure = new SockJsTransportFailureException(message, getSockJsUrlInfo().getSessionId(), null);
				}
				if (fallbackRequest != null) {
					logger.error(DefaultTransportRequest.this + " failed. Falling back on next transport.", failure);
					fallbackRequest.connect(this.handler, this.future);
				}
				else {
					logger.error("No more fallback transports after " + DefaultTransportRequest.this, failure);
					this.future.setException(failure);
				}
				if (isTimeoutFailure) {
					try {
						for (Runnable runnable : timeoutTasks) {
							runnable.run();
						}
					}
					catch (Throwable ex) {
						logger.error("Transport failed to run timeout tasks for " + DefaultTransportRequest.this, ex);
					}
				}
			}
			else {
				logger.error("Connect success/failure events already took place for " +
						DefaultTransportRequest.this + ". Ignoring this additional failure event.", failure);
			}
		}
	}
}
