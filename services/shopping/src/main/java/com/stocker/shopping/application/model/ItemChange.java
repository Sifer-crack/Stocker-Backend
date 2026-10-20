package com.stocker.shopping.application.model;

/** An item's new state after a comparison update, with the owner needed to route the notification. */
public record ItemChange(String userId, ItemView item) {
}
