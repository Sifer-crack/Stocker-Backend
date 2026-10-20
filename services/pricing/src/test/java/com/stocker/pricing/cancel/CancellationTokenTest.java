package com.stocker.pricing.cancel;

import io.grpc.Context;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CancellationTokenTest {

	@Test
	void startsNotCancelledAndCancelIsIdempotent() {
		CancellationToken token = CancellationToken.create();
		assertThat(token.isCancelled()).isFalse();
		assertThat(token.cancel()).isTrue();
		assertThat(token.cancel()).isFalse();
		assertThat(token.isCancelled()).isTrue();
	}

	@Test
	void callbacksRunOnceOnCancel() {
		CancellationToken token = CancellationToken.create();
		AtomicInteger runs = new AtomicInteger();
		token.onCancel(runs::incrementAndGet);
		token.cancel();
		token.cancel();
		assertThat(runs).hasValue(1);
	}

	@Test
	void callbackRegisteredAfterCancelRunsImmediately() {
		CancellationToken token = CancellationToken.create();
		token.cancel();
		AtomicInteger runs = new AtomicInteger();
		token.onCancel(runs::incrementAndGet);
		assertThat(runs).hasValue(1);
	}

	@Test
	void aClosedRegistrationNeverRuns() {
		CancellationToken token = CancellationToken.create();
		AtomicInteger runs = new AtomicInteger();
		token.onCancel(runs::incrementAndGet).close();
		token.cancel();
		assertThat(runs).hasValue(0);
	}

	@Test
	void aFailingCallbackDoesNotStopTheOthers() {
		CancellationToken token = CancellationToken.create();
		AtomicInteger runs = new AtomicInteger();
		token.onCancel(() -> {
			throw new IllegalStateException("boom");
		});
		token.onCancel(runs::incrementAndGet);
		token.cancel();
		assertThat(runs).hasValue(1);
	}

	@Test
	void throwIfCancelledThrowsOnlyAfterCancel() {
		CancellationToken token = CancellationToken.create();
		token.throwIfCancelled();
		token.cancel();
		assertThatThrownBy(token::throwIfCancelled).isInstanceOf(java.util.concurrent.CancellationException.class);
	}

	@Test
	void noneIsNeverCancelledAndCannotBeCancelled() {
		assertThat(CancellationToken.NONE.cancel()).isFalse();
		assertThat(CancellationToken.NONE.isCancelled()).isFalse();
		assertThat(CancellationToken.NONE.isCancellable()).isFalse();
		CancellationToken.NONE.throwIfCancelled();
	}

	@Test
	void sleepCompletesWhenNotCancelled() throws Exception {
		assertThat(CancellationToken.create().sleep(30)).isTrue();
	}

	@Test
	void sleepEndsPromptlyWhenCancelledFromAnotherThread() throws Exception {
		CancellationToken token = CancellationToken.create();
		new Thread(() -> {
			try {
				Thread.sleep(100);
			} catch (InterruptedException ignored) {
			}
			token.cancel();
		}).start();
		long start = System.nanoTime();
		boolean completed = token.sleep(10_000);
		assertThat(completed).isFalse();
		assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(2_000);
	}

	@Test
	void sleepOnNoneJustSleeps() throws Exception {
		assertThat(CancellationToken.NONE.sleep(20)).isTrue();
	}

	@Test
	void tryLockGivesUpWhenCancelledWhileWaiting() throws Exception {
		ReentrantLock lock = new ReentrantLock();
		CountDownLatch held = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		Thread holder = new Thread(() -> {
			lock.lock();
			held.countDown();
			try {
				release.await(10, TimeUnit.SECONDS);
			} catch (InterruptedException ignored) {
			} finally {
				lock.unlock();
			}
		});
		holder.start();
		held.await();
		CancellationToken token = CancellationToken.create();
		new Thread(() -> {
			try {
				Thread.sleep(100);
			} catch (InterruptedException ignored) {
			}
			token.cancel();
		}).start();
		assertThat(token.lockUnlessCancelled(lock)).isFalse();
		assertThat(lock.isHeldByCurrentThread()).isFalse();
		release.countDown();
		holder.join();
		assertThat(CancellationToken.create().lockUnlessCancelled(lock)).isTrue();
		lock.unlock();
	}

	@Test
	void aGrpcContextCancelledByTheClientCancelsTheToken() {
		Context.CancellableContext context = Context.current().withCancellation();
		CancellationToken token = CancellationToken.fromGrpcContext(context);
		assertThat(token.isCancelled()).isFalse();
		context.cancel(new RuntimeException("client went away"));
		assertThat(token.isCancelled()).isTrue();
	}

	@Test
	void aGrpcContextThatEndsNormallyDoesNotCancelTheToken() {
		// gRPC cancels the call Context (with no cause) when a call COMPLETES; background work the call started
		// must survive that.
		Context.CancellableContext context = Context.current().withCancellation();
		CancellationToken token = CancellationToken.fromGrpcContext(context);
		context.cancel(null);
		assertThat(token.isCancelled()).isFalse();
	}

	@Test
	void anAlreadyCancelledGrpcContextGivesACancelledToken() {
		Context.CancellableContext context = Context.current().withCancellation();
		context.cancel(new RuntimeException("gone"));
		assertThat(CancellationToken.fromGrpcContext(context).isCancelled()).isTrue();
	}

	@Test
	void theRootContextGivesTheUncancellableToken() {
		assertThat(CancellationToken.fromGrpcContext(Context.ROOT)).isSameAs(CancellationToken.NONE);
		assertThat(CancellationToken.current().isCancelled()).isFalse();
	}

	@Test
	void currentFollowsTheAttachedContext() throws Exception {
		Context.CancellableContext context = Context.current().withCancellation();
		CancellationToken token = context.call(CancellationToken::current);
		context.cancel(new RuntimeException("gone"));
		assertThat(token.isCancelled()).isTrue();
	}
}
