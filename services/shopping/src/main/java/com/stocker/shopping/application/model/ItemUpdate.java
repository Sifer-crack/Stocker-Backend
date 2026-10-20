package com.stocker.shopping.application.model;

/** Body of the shopping -> gateway push: who owns the item, how it was updated, and its full state. */
public record ItemUpdate(String userId, String source, ItemView item) {
}
