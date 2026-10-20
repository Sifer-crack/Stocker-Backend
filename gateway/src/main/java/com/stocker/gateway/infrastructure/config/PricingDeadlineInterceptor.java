package com.stocker.gateway.infrastructure.config;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.MethodDescriptor;

/**
 * Gives every outgoing pricing call a deadline (app.pricing.deadline, default 10 s) unless the caller already set a
 * shorter or equal one. Without it a hung pricing would keep a boundedElastic thread blocked in the blocking stub
 * indefinitely. The stubs are built with this interceptor, so controllers do not need to touch deadlines.
 */
public class PricingDeadlineInterceptor implements ClientInterceptor {

	private final Duration deadline;

	public PricingDeadlineInterceptor(Duration deadline) {
		this.deadline = deadline;
	}

	@Override
	public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method,
			CallOptions callOptions, Channel next) {
		if (callOptions.getDeadline() == null) {
			callOptions = callOptions.withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS);
		}
		return next.newCall(method, callOptions);
	}
}
