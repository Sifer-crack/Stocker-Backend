package com.stocker.pricing.service.search;

import com.stocker.pricing.model.NormalizedProduct;
import com.stocker.pricing.normalize.CommonProductCatalog;
import com.stocker.pricing.normalize.PackSize;
import com.stocker.pricing.normalize.PackSizeParser;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** Normalised-product rows shaped like the real ingest produces them (pack size from the name, catalog key). */
final class SearchFixtures {

	static final OffsetDateTime NOW = OffsetDateTime.now(ZoneOffset.UTC);
	static final CommonProductCatalog CATALOG = CommonProductCatalog.loadDefault();

	private SearchFixtures() {
	}

	static NormalizedProduct row(String chain, String name, String brand, String price) {
		return row(chain, name, brand, price, false, NOW);
	}

	static NormalizedProduct row(String chain, String name, String brand, String price, boolean promo,
			OffsetDateTime at) {
		BigDecimal amount = new BigDecimal(price);
		PackSize size = PackSizeParser.parse(name).orElse(null);
		return NormalizedProduct.builder().itemId(chain + ":" + name).storeId(chain + ":").chainId(chain)
				.canonicalKey(CATALOG.match(name).map(match -> match.key()).orElse(null))
				.normalizedName(name.toLowerCase()).displayName(name).brand(brand)
				.packUnit(size == null ? null : size.unit().code()).packQuantity(size == null ? null : size.quantity())
				.unitPrice(size == null ? null : size.unitPrice(amount)).priceAmount(amount).currency("NZD")
				.promoFlag(promo).capturedAt(at).updatedAt(at).build();
	}
}
