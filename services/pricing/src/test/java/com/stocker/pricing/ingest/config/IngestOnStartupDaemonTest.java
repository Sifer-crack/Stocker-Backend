package com.stocker.pricing.ingest.config;

import com.stocker.pricing.ingest.IngestScheduler;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A multi-minute paced startup scrape must never keep the JVM alive after a stop signal. */
class IngestOnStartupDaemonTest {

	@Test
	void theStartupRunIsADaemonThread() throws Exception {
		IngestScheduler scheduler = mock(IngestScheduler.class);
		IngestProperties properties = mock(IngestProperties.class);
		when(properties.isRunOnStartup()).thenReturn(true);
		CountDownLatch ran = new CountDownLatch(1);
		AtomicBoolean daemon = new AtomicBoolean(false);
		doAnswer(invocation -> {
			daemon.set(Thread.currentThread().isDaemon());
			ran.countDown();
			return null;
		}).when(scheduler).runAll();

		new IngestSchedulerConfig().ingestOnStartup(scheduler, properties).run(null);

		assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(daemon.get()).isTrue();
	}

	@Test
	void notConfiguredMeansNoRun() throws Exception {
		IngestScheduler scheduler = mock(IngestScheduler.class);
		IngestProperties properties = mock(IngestProperties.class);
		when(properties.isRunOnStartup()).thenReturn(false);

		new IngestSchedulerConfig().ingestOnStartup(scheduler, properties).run(null);

		verify(scheduler, never()).runAll();
	}
}
