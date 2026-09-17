package com.stocker.pricing.service;

import com.stocker.pricing.fetch.WebFetcher;
import com.stocker.pricing.fetch.model.RawProduct;
import com.stocker.pricing.fetch.model.WebFetchRequest;
import com.stocker.pricing.model.PriceRecord;
import com.stocker.pricing.repository.PriceRecordRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PriceSearchService {

	private static final Logger log = LoggerFactory.getLogger(PriceSearchService.class);

	private final WebFetcher webFetcher;
	private final PriceRecordRepository priceRecordRepository;

	public List<PriceRecord> search(String searchTerm, String itemId, List<String> storeUrls, String category) {
		WebFetchRequest request = WebFetchRequest.builder()
				.searchTerm(searchTerm)
				.storeUrls(storeUrls)
				.category(category)
				.build();
		try {
			List<RawProduct> products = webFetcher.fetch(request);
			List<PriceRecord> saved = products.stream()
					.map(product -> RawProductPriceRecordMapper.toPriceRecord(itemId, product))
					.map(priceRecordRepository::save)
					.toList();
			log.info("Web search saved {} price records for itemId={}, term={}", saved.size(), itemId, searchTerm);
			return saved;
		} catch (Exception e) {
			log.error("Web search failed for itemId={}, term={}", itemId, searchTerm, e);
			return List.of();
		}
	}
}