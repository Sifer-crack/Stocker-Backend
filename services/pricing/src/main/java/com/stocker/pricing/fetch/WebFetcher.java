package com.stocker.pricing.fetch;

import com.stocker.pricing.fetch.model.RawProduct;
import com.stocker.pricing.fetch.model.WebFetchRequest;
import java.util.List;

public interface WebFetcher {

	List<RawProduct> fetch(WebFetchRequest request);
}
