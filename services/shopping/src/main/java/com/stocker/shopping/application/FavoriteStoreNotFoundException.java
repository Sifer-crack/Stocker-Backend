package com.stocker.shopping.application;

import java.util.UUID;

public class FavoriteStoreNotFoundException extends RuntimeException {

	public FavoriteStoreNotFoundException(UUID id) {
		super("favorite store " + id + " not found");
	}
}
