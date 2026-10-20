package com.stocker.shopping.application.port;

import com.stocker.shopping.application.model.PricePoint;
import java.util.List;

/** Outbound port to the pricing service. Implementations must not retry. */
public interface PriceComparisonClient {

	/**
	 * @return every usable price found, or an empty list if pricing has nothing yet (a normal
	 *         outcome: the price events that follow are consumed to backfill)
	 * @throws PricingRejectedException      pricing refused the request (invalid / outside service area)
	 * @throws PricingUnavailableException   pricing failed, was unreachable, or exceeded the deadline
	 */
	List<PricePoint> compare(String itemId, String searchTerm, String category, String region);
}
