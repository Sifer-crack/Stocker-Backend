package com.stocker.pricing.ingest;

import com.microsoft.playwright.Browser;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One browser for all chains (the default, sequential mode). Uses the Spring-managed browser while it is connected;
 * when it has died (Firefox crashed or was killed) a replacement with its own Playwright driver is started, so the
 * next run does not fail at {@code newContext()} forever. The replacement is closed with this pool; the original is
 * closed by Spring.
 */
public class SelfHealingSharedBrowser implements ChainBrowsers {

	private static final Logger log = LoggerFactory.getLogger(SelfHealingSharedBrowser.class);

	private final Object key = new Object();
	private final Browser original;
	private final Supplier<ManagedBrowser> replacementFactory;
	private ManagedBrowser replacement;
	private boolean closed;

	public SelfHealingSharedBrowser(Browser original, Supplier<ManagedBrowser> replacementFactory) {
		this.original = original;
		this.replacementFactory = replacementFactory;
	}

	@Override
	public synchronized Browser forChain(ChainId chain) {
		if (closed) {
			throw new IllegalStateException("browser is closed (shutting down)");
		}
		if (replacement != null) {
			if (connected(replacement.browser())) {
				return replacement.browser();
			}
			closeReplacement();
		} else if (connected(original)) {
			return original;
		}
		log.warn("The shared browser is no longer connected, starting a new one");
		replacement = replacementFactory.get();
		return replacement.browser();
	}

	private static boolean connected(Browser browser) {
		try {
			return browser.isConnected();
		} catch (RuntimeException e) {
			return false;
		}
	}

	@Override
	public Object lockKey(ChainId chain) {
		return key;
	}

	@Override
	public synchronized void close() {
		closed = true;
		closeReplacement();
	}

	private void closeReplacement() {
		if (replacement == null) {
			return;
		}
		try {
			replacement.close();
		} catch (RuntimeException e) {
			log.warn("closing the replacement browser failed: {}", e.getMessage());
		} finally {
			replacement = null;
		}
	}
}
