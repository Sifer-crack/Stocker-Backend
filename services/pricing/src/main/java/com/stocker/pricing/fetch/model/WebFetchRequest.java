package com.stocker.pricing.fetch.model;

import com.stocker.pricing.cancel.CancellationToken;
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
	/** Cancelled when the caller abandons the request: fetchers send nothing further and drop the call in flight. */
	@Builder.Default
	private CancellationToken cancellation = CancellationToken.NONE;
}
