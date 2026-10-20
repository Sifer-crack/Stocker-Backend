package com.stocker.notifications.infrastructure.scheduling;

import com.stocker.notifications.application.ExpirationScanService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ExpirationScanScheduler {

	private static final Logger log = LoggerFactory.getLogger(ExpirationScanScheduler.class);

	private final ExpirationScanService scanService;

	public ExpirationScanScheduler(ExpirationScanService scanService) {
		this.scanService = scanService;
	}

	@Scheduled(fixedDelayString = "${app.notifications.scan-interval:PT15M}",
			initialDelayString = "${app.notifications.scan-initial-delay:PT30S}")
	public void runScan() {
		try {
			scanService.scan();
		}
		catch (RuntimeException e) {
			// inventory down or unreachable: log and try again on the next tick
			log.warn("Expiry scan failed, will retry on the next run: {}", e.getMessage());
		}
	}
}
