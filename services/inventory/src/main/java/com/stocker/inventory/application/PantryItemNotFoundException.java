package com.stocker.inventory.application;

/** The pantry item does not exist or belongs to someone else (deliberately indistinguishable). */
public class PantryItemNotFoundException extends RuntimeException {
    public PantryItemNotFoundException() {
        super("Pantry item not found");
    }
}
