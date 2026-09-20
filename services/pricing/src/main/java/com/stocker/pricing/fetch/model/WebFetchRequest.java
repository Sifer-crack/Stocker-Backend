package com.stocker.pricing.fetch.model;

import java.math.BigDecimal;
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
public class WebFetchRequest {

	private String searchTerm;
	private List<String> storeUrls;
	private String category;
	private BigDecimal minPrice;
	private BigDecimal maxPrice;
	private String locale;
}
