package com.stocker.pricing.ingest.model;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChainScraperConfig {

	private String storeId;
	private String storeName;
	private List<String> categoryUrls;
	private int maxPagesPerCategory;
	private int navigationTimeoutSeconds;
}
