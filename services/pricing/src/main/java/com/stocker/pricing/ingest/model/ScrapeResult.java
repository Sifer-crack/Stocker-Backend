package com.stocker.pricing.ingest.model;

import com.stocker.pricing.fetch.model.RawProduct;
import com.stocker.pricing.ingest.ChainId;
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
public class ScrapeResult {

	private ChainId chainId;
	private List<RawProduct> products;
	private List<String> categoriesVisited;
	private List<String> zeroResultCategoryUrls;
	private List<String> warnings;
}
