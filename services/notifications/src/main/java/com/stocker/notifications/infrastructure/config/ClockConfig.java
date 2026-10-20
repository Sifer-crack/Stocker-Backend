package com.stocker.notifications.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
@EnableScheduling
public class ClockConfig {

	/** "Today" for expiry is the user's calendar day, not UTC's: the default zone is New Zealand. */
	@Bean
	Clock clock(@Value("${app.notifications.zone:Pacific/Auckland}") String zone) {
		return Clock.system(ZoneId.of(zone));
	}
}
