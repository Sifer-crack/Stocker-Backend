package com.stocker.pricing.ingest.config;

import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.ingest")
public class IngestProperties {

	private boolean enabled = false;
	private boolean dryRun = true;
	private String cron = "0 0 3 * * *";
	private int navigationTimeoutSeconds = 30;
	private int maxPagesPerCategory = 5;
	private int sitemapCacheTtlDays = 7;
	private ChainConfig newworld = new ChainConfig();
	private ChainConfig paknsave = new ChainConfig();
	private ChainConfig woolworths = new ChainConfig();

	@Getter
	@Setter
	public static class ChainConfig {
		private boolean enabled = false;
		private String storeId = "";
		private String storeName = "";
		private List<String> categoryUrls = List.of();
	}
}
