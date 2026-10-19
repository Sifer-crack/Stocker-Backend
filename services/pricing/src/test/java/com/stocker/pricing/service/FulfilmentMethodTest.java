package com.stocker.pricing.service;

import com.stocker.pricing.service.fulfilment.FulfilmentMethod;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FulfilmentMethodTest {

	@Test
	void wireNamesAreTheContractNames() {
		assertEquals("delivery", FulfilmentMethod.DELIVERY.wireName());
		assertEquals("click_and_collect", FulfilmentMethod.CLICK_AND_COLLECT.wireName());
	}

	@Test
	void declarationOrderIsTheTieBreakOrder() {
		assertEquals(List.of(FulfilmentMethod.CLICK_AND_COLLECT, FulfilmentMethod.DELIVERY),
				List.of(FulfilmentMethod.values()));
	}

	@Test
	void parsesWireNamesAndRejectsUnknownOnes() {
		assertEquals(FulfilmentMethod.CLICK_AND_COLLECT, FulfilmentMethod.fromWireName("click_and_collect"));
		assertEquals(FulfilmentMethod.DELIVERY, FulfilmentMethod.fromWireName(" Delivery "));
		IllegalArgumentException ex =
				org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> FulfilmentMethod.fromWireName("drone"));
		assertTrue(ex.getMessage().contains("delivery") && ex.getMessage().contains("click_and_collect"),
				"message lists the valid names");
		assertTrue(ex.getMessage().contains("click_and_collect, delivery"), "in tie-break order");
	}

	@Test
	void inStoreIsNoLongerAMethod() {
		assertEquals(2, FulfilmentMethod.values().length);
		IllegalArgumentException ex = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
				() -> FulfilmentMethod.fromWireName("in_store"));
		assertTrue(ex.getMessage().contains("in_store") && ex.getMessage().contains("click_and_collect, delivery"));
	}
}
