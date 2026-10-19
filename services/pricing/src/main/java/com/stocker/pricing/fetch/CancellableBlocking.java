package com.stocker.pricing.fetch;

import com.stocker.pricing.cancel.CancellationToken;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import reactor.core.publisher.Mono;

/**
 * The one way fetchers wait for an outbound HTTP call: like {@code Mono.block()}, but nothing is sent when the request
 * is already cancelled, and cancelling the request disposes the in-flight call (closing the connection).
 */
public final class CancellableBlocking {

	private CancellableBlocking() {
	}

	public static <T> T block(Mono<T> call, CancellationToken token) {
		token.throwIfCancelled();
		CompletableFuture<T> future = call.toFuture();      // cancelling the future disposes the subscription
		try (CancellationToken.Registration ignored = token.onCancel(() -> future.cancel(true))) {
			return future.get();
		} catch (CancellationException e) {
			throw e;
		} catch (InterruptedException e) {
			future.cancel(true);
			Thread.currentThread().interrupt();
			throw new CancellationException("interrupted while waiting for the outbound call");
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof RuntimeException runtime) {
				throw runtime;
			}
			throw new IllegalStateException(cause);
		}
	}
}
