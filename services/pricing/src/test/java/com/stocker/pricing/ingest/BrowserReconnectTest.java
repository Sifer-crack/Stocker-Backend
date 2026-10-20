package com.stocker.pricing.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.microsoft.playwright.Browser;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A browser that died (Firefox crashed or was killed) is replaced instead of failing every later scrape. */
class BrowserReconnectTest {

	private final List<ManagedBrowser> started = new ArrayList<>();
	private final List<ManagedBrowser> closed = new ArrayList<>();

	private static Browser browser(boolean connected) {
		Browser browser = mock(Browser.class);
		when(browser.isConnected()).thenReturn(connected);
		return browser;
	}

	private ManagedBrowser managed(Browser browser) {
		ManagedBrowser managed = new ManagedBrowser() {
			public Browser browser() {
				return browser;
			}

			public void close() {
				closed.add(this);
			}
		};
		started.add(managed);
		return managed;
	}

	@Test
	void perChainBrowserIsRecreatedWhenItIsNoLongerConnected() {
		Browser dead = browser(true);
		Browser fresh = browser(true);
		List<Browser> queue = new ArrayList<>(List.of(dead, fresh));
		PerChainBrowsers browsers = new PerChainBrowsers(() -> managed(queue.remove(0)));

		assertThat(browsers.forChain(ChainId.NEWWORLD)).isSameAs(dead);
		when(dead.isConnected()).thenReturn(false);

		assertThat(browsers.forChain(ChainId.NEWWORLD)).isSameAs(fresh);
		assertThat(closed).hasSize(1);                  // the dead one's driver is closed
		assertThat(started).hasSize(2);
		assertThat(browsers.openCount()).isEqualTo(1);
	}

	@Test
	void aConnectedPerChainBrowserIsReused() {
		PerChainBrowsers browsers = new PerChainBrowsers(() -> managed(browser(true)));

		Browser first = browsers.forChain(ChainId.PAKNSAVE);

		assertThat(browsers.forChain(ChainId.PAKNSAVE)).isSameAs(first);
		assertThat(started).hasSize(1);
	}

	@Test
	void theSharedBrowserIsReplacedWhenItDiesAndTheReplacementIsClosedWithThePool() {
		Browser original = browser(true);
		Browser replacement = browser(true);
		SelfHealingSharedBrowser pool = new SelfHealingSharedBrowser(original, () -> managed(replacement));

		assertThat(pool.forChain(ChainId.NEWWORLD)).isSameAs(original);
		when(original.isConnected()).thenReturn(false);
		assertThat(pool.forChain(ChainId.PAKNSAVE)).isSameAs(replacement);
		assertThat(pool.forChain(ChainId.WOOLWORTHS)).isSameAs(replacement);
		assertThat(started).hasSize(1);

		pool.close();
		assertThat(closed).hasSize(1);
		assertThatThrownBy(() -> pool.forChain(ChainId.NEWWORLD)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void aReplacementThatDiesIsReplacedAgainAndTheOldOneClosed() {
		Browser original = browser(false);
		Browser first = browser(true);
		Browser second = browser(true);
		List<Browser> queue = new ArrayList<>(List.of(first, second));
		SelfHealingSharedBrowser pool = new SelfHealingSharedBrowser(original, () -> managed(queue.remove(0)));

		assertThat(pool.forChain(ChainId.NEWWORLD)).isSameAs(first);
		when(first.isConnected()).thenReturn(false);

		assertThat(pool.forChain(ChainId.NEWWORLD)).isSameAs(second);
		assertThat(closed).hasSize(1);
	}

	@Test
	void allChainsShareOneLockKey() {
		SelfHealingSharedBrowser pool = new SelfHealingSharedBrowser(browser(true), () -> managed(browser(true)));

		assertThat(pool.lockKey(ChainId.NEWWORLD)).isSameAs(pool.lockKey(ChainId.WOOLWORTHS));
	}
}
