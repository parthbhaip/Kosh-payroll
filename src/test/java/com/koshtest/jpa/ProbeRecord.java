package com.koshtest.jpa;

import jakarta.persistence.Convert;
import jakarta.persistence.Entity;

import com.kosh.common.BaseEntity;
import com.kosh.common.EncryptedStringConverter;

/**
 * Test-only entity for {@code BaseEntityJpaTest}. It lives outside {@code com.kosh} on purpose: the application scans
 * {@code com.kosh} for entities and validates them against the Flyway schema, which has no table for this one.
 */
@Entity
public class ProbeRecord extends BaseEntity {

	private String label;

	@Convert(converter = EncryptedStringConverter.class)
	private String secret;

	protected ProbeRecord() {
	}

	public ProbeRecord(String label, String secret) {
		this.label = label;
		this.secret = secret;
	}

	public String getLabel() {
		return label;
	}

	public void setLabel(String label) {
		this.label = label;
	}

	public String getSecret() {
		return secret;
	}

}
