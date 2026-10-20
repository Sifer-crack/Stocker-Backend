package com.stocker.shopping.infrastructure.config;

import java.time.Clock;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bounded background executors. Comparison calls (up to the gRPC deadline each) and gateway
 * notifications are kept on separate pools so a slow pricing service cannot delay pushes, and a
 * slow gateway cannot stall comparisons. Bounded queues: when full, work is rejected and callers
 * degrade (the item stays pending and is backfilled) instead of the request thread ever blocking.
 */
@Configuration
public class AsyncConfig {

	@Bean(destroyMethod = "shutdown")
	public ExecutorService comparisonExecutor() {
		return pool("comparison", 4, 100);
	}

	@Bean(destroyMethod = "shutdown")
	public ExecutorService notificationExecutor() {
		return pool("notification", 2, 200);
	}

	@Bean
	public Clock clock() {
		return Clock.systemUTC();
	}

	private static ExecutorService pool(String name, int threads, int queueCapacity) {
		AtomicInteger counter = new AtomicInteger();
		ThreadPoolExecutor executor = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS,
				new ArrayBlockingQueue<>(queueCapacity), runnable -> {
					Thread thread = new Thread(runnable, name + "-" + counter.incrementAndGet());
					thread.setDaemon(true);
					return thread;
				});
		executor.allowCoreThreadTimeOut(true);
		return executor;
	}
}
