package com.stocker.inventory.api.rest;

/** The X-User-Id header the gateway sets from the verified JWT is missing or not a UUID. */
public class UnauthorizedException extends RuntimeException {
    public UnauthorizedException(String message) {
        super(message);
    }
}
