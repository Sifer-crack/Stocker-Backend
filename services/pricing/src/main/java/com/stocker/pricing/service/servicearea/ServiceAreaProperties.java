package com.stocker.pricing.service.servicearea;

import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.service-area")
public class ServiceAreaProperties {

	private List<String> supportedRegions = List.of();

	public boolean isSupported(String region) {
		if (!StringUtils.hasText(region)) {
			return false;
		}
		String trimmed = region.trim();
		return supportedRegions.stream().anyMatch(supported -> supported.equalsIgnoreCase(trimmed));
	}
}
