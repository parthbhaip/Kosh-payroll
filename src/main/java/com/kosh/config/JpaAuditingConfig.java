package com.kosh.config;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Fills {@code createdAt}/{@code updatedAt} of every {@code BaseEntity} from the application clock. Kept apart from
 * {@link TimeConfig} so web slice tests can import the clock without needing a JPA metamodel.
 */
@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
public class JpaAuditingConfig {

	/** Truncated to microseconds, the precision of {@code DATETIME(6)}, so the entity matches what was stored. */
	@Bean
	DateTimeProvider auditingDateTimeProvider(Clock clock) {
		return () -> Optional.of(LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS));
	}

}
