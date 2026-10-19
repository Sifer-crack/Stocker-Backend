package com.stocker.pricing.ingest;

import com.microsoft.playwright.Browser;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Each chain gets its own browser, started on first use, so chains can be crawled at the same time. */
public class PerChainBrowsers implements ChainBrowsers {

	private static final Logger log = LoggerFactory.getLogger(PerChainBrowsers.class);

	private final Supplier<ManagedBrowser> factory;
	private final Map<ChainId, ManagedBrowser> browsers = new EnumMap<>(ChainId.class);
	private final Map<ChainId, Object> keys = new EnumMap<>(ChainId.class);
	private boolean closed;

	public PerChainBrowsers(Supplier<ManagedBrowser> factory) {
		this.factory = factory;
		for (ChainId chain : ChainId.values()) {
			keys.put(chain, new Object());
		}
	}

	@Override
	public synchronized Browser forChain(ChainId chain) {
		if (closed) {
			throw new IllegalStateException("browsers are closed (shutting down)");
		}
		ManagedBrowser managed = browsers.get(chain);
		if (managed == null) {
			managed = factory.get();
			browsers.put(chain, managed);
			log.info("{}: started its own browser", chain);
		}
		return managed.browser();
	}

	@Override
	public Object lockKey(ChainId chain) {
		return keys.get(chain);
	}

	@Override
	public synchronized void close() {
		closed = true;
		List<ManagedBrowser> toClose = new ArrayList<>(browsers.values());
		browsers.clear();
		for (ManagedBrowser managed : toClose) {
			try {
				managed.close();
			} catch (RuntimeException e) {
				log.warn("closing a chain browser failed: {}", e.getMessage());
			}
		}
	}

	/** Number of browsers currently started (for tests and logs). */
	public synchronized int openCount() {
		return browsers.size();
	}
}
