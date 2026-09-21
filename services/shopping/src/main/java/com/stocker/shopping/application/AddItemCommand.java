package com.stocker.shopping.application;

public record AddItemCommand(String userId, String householdId, String name, String sku, String category,
		String region, int quantity) {
}
