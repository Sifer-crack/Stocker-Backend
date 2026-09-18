package com.stocker.pricing.service.cache;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.pricing")
public class PricingCacheProperties {

	private Cache cache = new Cache();
	private Fallback fallback = new Fallback();
	private Refresh refresh = new Refresh();

	@Getter
	@Setter
	public static class Cache {
		private Duration freshnessWindow = Duration.ofHours(24);
		private Duration l1Ttl = Duration.ofMinutes(5);
	}

	@Getter
	@Setter
	public static class Fallback {
		private Duration timeout = Duration.ofSeconds(3);
	}

	@Getter
	@Setter
	public static class Refresh {
		private String topic = "stocker.pricing.refresh-requests.v1";
	}
}
