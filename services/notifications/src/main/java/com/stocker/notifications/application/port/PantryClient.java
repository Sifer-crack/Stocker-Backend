package com.stocker.notifications.application.port;

import com.stocker.notifications.application.model.ExpiringItem;

import java.time.LocalDate;
import java.util.List;

public interface PantryClient {

	/** Pantry items whose notification window has opened on {@code asOf}. */
	List<ExpiringItem> findDueItems(LocalDate asOf);
}
