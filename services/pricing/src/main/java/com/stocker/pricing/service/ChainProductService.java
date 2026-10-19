package com.stocker.pricing.service;

import com.stocker.pricing.ingest.ChainId;
import com.stocker.pricing.model.PriceRecord;
import com.stocker.pricing.repository.PriceRecordRepository;
import com.stocker.pricing.service.cache.PricingCacheProperties;
import com.stocker.pricing.service.match.NameMatcher;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Lists the products stored for one supermarket chain, so a user can browse a favorite store. Reads only what
 * the scheduled ingest already stored (the same window as {@code /match}); it never triggers a request to a
 * supermarket.
 */
@Service
public class ChainProductService {

	public static final int DEFAULT_LIMIT = 50;
	public static final int MAX_LIMIT = 200;

	/** New World / PAK'nSave scrape the card's subtitle (the pack size, e.g. "2l") into the brand slot. */
	private static final Pattern SIZE_LIKE = Pattern.compile("^\\d+(?:\\.\\d+)?\\s*(?:l|ml|g|kg)$", Pattern.CASE_INSENSITIVE);

	public record ChainProduct(String itemId, String name, String brand, BigDecimal priceAmount, String currency,
			boolean promoFlag, String packSize, OffsetDateTime capturedAt) {
	}

	private record Scored(ChainProduct product, double score) {
	}

	private final PriceRecordRepository repository;
	private final PricingCacheProperties properties;

	public ChainProductService(PriceRecordRepository repository, PricingCacheProperties properties) {
		this.repository = repository;
		this.properties = properties;
	}

	/**
	 * @param query optional words; when given, only matching products are returned, best match first
	 * @param limit 0 or less means {@link #DEFAULT_LIMIT}; never more than {@link #MAX_LIMIT}
	 * @throws IllegalArgumentException for a chain pricing does not know
	 */
	public List<ChainProduct> list(String chainId, String query, int limit) {
		String chain = normaliseChain(chainId);
		int max = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
		OffsetDateTime since = OffsetDateTime.now(ZoneOffset.UTC).minus(properties.getMatch().getMaxAge());

		Map<String, PriceRecord> latestPerProduct = new LinkedHashMap<>();
		for (PriceRecord row : repository.findIngestedByChainSince(chain, since)) {
			latestPerProduct.merge(row.getItemId(), row,
					(existing, candidate) -> candidate.getCapturedAt().isAfter(existing.getCapturedAt()) ? candidate : existing);
		}

		boolean searching = StringUtils.hasText(query);
		double minScore = properties.getMatch().getAlternativeMinScore();
		List<Scored> found = new ArrayList<>();
		for (PriceRecord row : latestPerProduct.values()) {
			String name = nameOf(row);
			if (!StringUtils.hasText(name) || row.getPriceAmount() == null || row.getPriceAmount().signum() <= 0) {
				continue;
			}
			double score = searching ? NameMatcher.score(query, name) : 1.0;
			if (searching && score < minScore) {
				continue;
			}
			found.add(new Scored(toProduct(row, name), score));
		}
		Comparator<Scored> order = searching
				? Comparator.comparingDouble(Scored::score).reversed()
						.thenComparing(scored -> scored.product().name(), String.CASE_INSENSITIVE_ORDER)
				: Comparator.comparing(scored -> scored.product().name(), String.CASE_INSENSITIVE_ORDER);
		return found.stream().sorted(order).limit(max).map(Scored::product).toList();
	}

	private static String normaliseChain(String chainId) {
		String chain = chainId == null ? "" : chainId.trim().toLowerCase(Locale.ROOT);
		try {
			ChainId.valueOf(chain.toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("Unknown store chain '" + (chainId == null ? "" : chainId.trim()) + "'");
		}
		return chain;
	}

	private static ChainProduct toProduct(PriceRecord row, String name) {
		return new ChainProduct(row.getItemId(), name, realBrand(row), row.getPriceAmount(), row.getCurrency(),
				row.isPromoFlag(), packSize(row), row.getCapturedAt());
	}

	// Same name/brand/pack-size rules as ItemMatchService (private there); fold into one helper when the
	// normalised-products work (StoreProductText) is merged.
	private static String nameOf(PriceRecord row) {
		String name = attribute(row, "name");
		if (name == null) {
			return null;
		}
		String size = packSize(row);
		return size == null || name.toLowerCase(Locale.ROOT).contains(size.toLowerCase(Locale.ROOT))
				? name
				: name + " " + size;
	}

	private static String packSize(PriceRecord row) {
		String brand = attribute(row, "brand");
		return brand != null && SIZE_LIKE.matcher(brand.trim()).matches() ? brand.trim() : null;
	}

	private static String realBrand(PriceRecord row) {
		return packSize(row) == null ? attribute(row, "brand") : null;
	}

	private static String attribute(PriceRecord row, String key) {
		Object value = row.getRawAttributes() == null ? null : row.getRawAttributes().get(key);
		return value == null || value.toString().isBlank() ? null : value.toString();
	}
}
