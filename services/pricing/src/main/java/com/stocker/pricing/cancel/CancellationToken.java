package com.stocker.pricing.cancel;

import io.grpc.Context;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thread-safe "the request this work belongs to has been abandoned" flag, handed down the call chain so work started
 * on other threads (background page loads, outbound HTTP calls) can stop. Cancelling runs the registered callbacks
 * once; callbacks registered after the cancel run at once. {@link #NONE} is never cancelled (scheduled crawls and any
 * caller that is not request-bound).
 */
public final class CancellationToken {

	private static final Logger log = LoggerFactory.getLogger(CancellationToken.class);

	/** Never cancelled and cannot be: work that must not follow a client disconnect. */
	public static final CancellationToken NONE = new CancellationToken(false);

	/** Handle to a registered callback; closing it removes the callback if it has not run. */
	public interface Registration extends AutoCloseable {
		@Override
		void close();
	}

	private static final Registration NOOP = () -> { };

	private final boolean cancellable;
	private final CountDownLatch cancelled = new CountDownLatch(1);
	private final CopyOnWriteArrayList<Runnable> callbacks = new CopyOnWriteArrayList<>();

	private CancellationToken(boolean cancellable) {
		this.cancellable = cancellable;
	}

	public static CancellationToken create() {
		return new CancellationToken(true);
	}

	/**
	 * A token that is cancelled when the gRPC call owning {@code context} is aborted by the client or its deadline.
	 * gRPC also ends the Context, with no cause, when a call COMPLETES normally; that must not stop background
	 * work the call started, so only a cancellation with a cause counts.
	 */
	public static CancellationToken fromGrpcContext(Context context) {
		if (context == null || context == Context.ROOT) {
			return NONE;
		}
		CancellationToken token = create();
		if (context.isCancelled() && context.cancellationCause() != null) {
			token.cancel();
			return token;
		}
		context.addListener(ended -> {
			if (ended.cancellationCause() != null) {
				token.cancel();
			}
		}, Runnable::run);
		return token;
	}

	/** Token for the gRPC call running on the current thread (NONE outside a call). */
	public static CancellationToken current() {
		return fromGrpcContext(Context.current());
	}

	public boolean isCancellable() {
		return cancellable;
	}

	public boolean isCancelled() {
		return cancelled.getCount() == 0;
	}

	/** @return true when this call did the cancelling (false if already cancelled, or {@link #NONE}) */
	public boolean cancel() {
		if (!cancellable) {
			return false;
		}
		synchronized (callbacks) {
			if (isCancelled()) {
				return false;
			}
			cancelled.countDown();
		}
		for (Runnable callback : callbacks) {
			run(callback);
		}
		callbacks.clear();
		return true;
	}

	/** Runs {@code callback} once when cancelled (immediately if already cancelled). */
	public Registration onCancel(Runnable callback) {
		if (!cancellable) {
			return NOOP;
		}
		synchronized (callbacks) {
			if (!isCancelled()) {
				callbacks.add(callback);
				return () -> callbacks.remove(callback);
			}
		}
		run(callback);
		return NOOP;
	}

	public void throwIfCancelled() {
		if (isCancelled()) {
			throw new CancellationException("request cancelled");
		}
	}

	/**
	 * Sleeps up to {@code millis}, waking at once if cancelled.
	 *
	 * @return true if the full time passed, false if cancelled first
	 */
	public boolean sleep(long millis) throws InterruptedException {
		if (millis <= 0) {
			return !isCancelled();
		}
		if (!cancellable) {
			Thread.sleep(millis);
			return true;
		}
		return !cancelled.await(millis, TimeUnit.MILLISECONDS);
	}

	/**
	 * Takes {@code lock}, giving up when cancelled while waiting.
	 *
	 * @return true if the lock is now held by the caller (who must unlock), false if cancelled (not held)
	 */
	public boolean lockUnlessCancelled(Lock lock) {
		if (!cancellable) {
			lock.lock();
			return true;
		}
		try {
			while (!lock.tryLock(100, TimeUnit.MILLISECONDS)) {
				if (isCancelled()) {
					return false;
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
		if (isCancelled()) {
			lock.unlock();
			return false;
		}
		return true;
	}

	private static void run(Runnable callback) {
		try {
			callback.run();
		} catch (RuntimeException e) {
			log.warn("Cancellation callback failed: {}", e.toString());
		}
	}
}
