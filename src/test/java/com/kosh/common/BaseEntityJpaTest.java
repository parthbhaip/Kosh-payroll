package com.kosh.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.kosh.config.AppProperties;
import com.kosh.config.JpaAuditingConfig;
import com.kosh.config.TimeConfig;
import com.koshtest.jpa.ProbeRecord;

/**
 * BaseEntity auditing and versioning, and the encrypted column, through real Hibernate. The probe table is created by
 * Hibernate in a throw-away embedded database, because the Flyway schema has no table for it. Only the probe entity is
 * mapped, so the application's repositories (whose entities are not scanned here) stay switched off.
 */
@DataJpaTest(properties = { "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
		"spring.data.jpa.repositories.enabled=false" })
@ActiveProfiles("test")
class BaseEntityJpaTest {

	/** The fixed test clock: 5 Oct 2026, 10:00 in Kolkata. */
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 5, 10, 0);

	private final TestEntityManager em;

	private final AppProperties app;

	@Autowired
	BaseEntityJpaTest(TestEntityManager em, AppProperties app) {
		this.em = em;
		this.app = app;
	}

	@Test
	void newRowGetsIdTimestampsFromTheClockAndVersionZero() {
		ProbeRecord saved = em.persistFlushFind(new ProbeRecord("first", null));

		assertThat(saved.getId()).isNotNull();
		assertThat(saved.getCreatedAt()).isEqualTo(NOW);
		assertThat(saved.getUpdatedAt()).isEqualTo(NOW);
		assertThat(saved.getVersion()).isZero();
	}

	@Test
	void updateMovesUpdatedAtAndVersionButNeverCreatedAt() {
		Long id = em.persistAndFlush(new ProbeRecord("first", null)).getId();
		LocalDateTime lastWeek = NOW.minusDays(7);
		em.getEntityManager()
			.createNativeQuery("UPDATE probe_record SET created_at = ?1, updated_at = ?1 WHERE id = ?2")
			.setParameter(1, lastWeek)
			.setParameter(2, id)
			.executeUpdate();
		em.clear();

		ProbeRecord loaded = em.find(ProbeRecord.class, id);
		loaded.setLabel("second");
		em.flush();
		em.clear();

		ProbeRecord updated = em.find(ProbeRecord.class, id);
		assertThat(updated.getLabel()).isEqualTo("second");
		assertThat(updated.getCreatedAt()).isEqualTo(lastWeek);
		assertThat(updated.getUpdatedAt()).isEqualTo(NOW);
		assertThat(updated.getVersion()).isEqualTo(1L);
	}

	@Test
	void encryptedColumnStoresCiphertextUnderTheAppKey() {
		Long id = em.persistAndFlush(new ProbeRecord("pan", "ABCDE1234F")).getId();
		String stored = (String) em.getEntityManager()
			.createNativeQuery("SELECT secret FROM probe_record WHERE id = ?1")
			.setParameter(1, id)
			.getSingleResult();
		em.clear();

		assertThat(stored).doesNotContain("ABCDE1234F");
		assertThat(Base64.getDecoder().decode(stored)).hasSize(12 + 10 + 16);
		assertThat(new EncryptedStringConverter(app.dataKeyBytes()).convertToEntityAttribute(stored))
			.isEqualTo("ABCDE1234F");
		assertThat(em.find(ProbeRecord.class, id).getSecret()).isEqualTo("ABCDE1234F");
	}

	@TestConfiguration(proxyBeanMethods = false)
	@EntityScan(basePackageClasses = ProbeRecord.class)
	@EnableConfigurationProperties(AppProperties.class)
	@Import({ TimeConfig.class, JpaAuditingConfig.class })
	static class ProbeConfig {

	}

}
