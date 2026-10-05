package com.kosh.config;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.Profiles;

/**
 * Tests run on a fixed clock (CLAUDE.md > Testing rules): under the {@code test} profile every context gets a
 * {@code @Primary} clock stopped at {@link #NOW}, so services, JPA auditing and {@code @fmt} all see the same "now".
 * The application's own system clock bean stays in the context. Registered in
 * {@code src/test/resources/META-INF/spring.factories}.
 */
public class TestClockInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

	/** Monday 5 October 2026, 10:00 in Kolkata. */
	public static final Instant NOW = Instant.parse("2026-10-05T04:30:00Z");

	public static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("Asia/Kolkata"));

	@Override
	public void initialize(ConfigurableApplicationContext context) {
		if (!context.getEnvironment().acceptsProfiles(Profiles.of("test"))) {
			return;
		}
		if (!(context instanceof GenericApplicationContext generic)) {
			throw new IllegalStateException("Cannot register the fixed test clock in " + context.getClass().getName());
		}
		generic.registerBean("fixedTestClock", Clock.class, () -> CLOCK, (definition) -> definition.setPrimary(true));
	}

}
