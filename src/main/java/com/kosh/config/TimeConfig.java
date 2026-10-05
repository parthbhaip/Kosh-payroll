package com.kosh.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The one source of "now": inject {@link Clock} and never call {@code LocalDate.now()} without it. */
@Configuration(proxyBeanMethods = false)
public class TimeConfig {

	@Bean
	Clock clock(AppProperties app) {
		return Clock.system(app.zone());
	}

}
