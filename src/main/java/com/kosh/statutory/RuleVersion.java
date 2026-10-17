package com.kosh.statutory;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.kosh.common.BaseEntity;

/**
 * One version of a rule family: in force from {@code effectiveFrom} to {@code effectiveTo} (null: until a later version
 * starts). Each family's values live in its own table, joined by id. Values never change once saved; adding a later
 * version closes this one (withdrawing that one before it starts opens this one again), and an admin may record that it
 * was checked again ({@code verifiedOn}). Changed only through
 * {@link StatutoryRuleService}.
 */
@Entity
@Table(name = "statutory_rules")
@Inheritance(strategy = InheritanceType.JOINED)
public abstract class RuleVersion extends BaseEntity {

	/** The entity type in the audit log. */
	public static final String AUDIT_TYPE = "Statutory rule";

	public static final int MAX_SOURCE_NOTE = 1000;

	/** {@code scope} of a rule that applies across India. */
	static final String NATIONAL = "IN";

	@Column(nullable = false, length = 20)
	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	private RuleFamily family;

	@Column(nullable = false, length = 2)
	private String scope;

	@Column(nullable = false)
	private LocalDate effectiveFrom;

	private LocalDate effectiveTo;

	@Column(nullable = false, length = MAX_SOURCE_NOTE)
	private String sourceNote;

	@Column(nullable = false)
	private LocalDate verifiedOn;

	protected RuleVersion() {
	}

	RuleVersion(RuleFamily family, String scope, LocalDate effectiveFrom, String sourceNote, LocalDate verifiedOn) {
		this.family = family;
		this.scope = scope;
		this.effectiveFrom = effectiveFrom;
		this.sourceNote = sourceNote;
		this.verifiedOn = verifiedOn;
	}

	/** The family's values in this version. */
	public abstract Rules rules();

	public RuleFamily getFamily() {
		return family;
	}

	public String getScope() {
		return scope;
	}

	public LocalDate getEffectiveFrom() {
		return effectiveFrom;
	}

	public LocalDate getEffectiveTo() {
		return effectiveTo;
	}

	public String getSourceNote() {
		return sourceNote;
	}

	public LocalDate getVerifiedOn() {
		return verifiedOn;
	}

	public boolean inForceOn(LocalDate date) {
		return !date.isBefore(effectiveFrom) && (effectiveTo == null || !date.isAfter(effectiveTo));
	}

	/** Ends this version on {@code lastDay}, because a later one starts the day after. */
	void closeOn(LocalDate lastDay) {
		if (effectiveTo != null || lastDay.isBefore(effectiveFrom)) {
			throw new IllegalStateException("Version " + getId() + " cannot end on " + lastDay);
		}
		this.effectiveTo = lastDay;
	}

	/** Removes the end date again, because the version after this one was withdrawn before it started. */
	void reopen() {
		this.effectiveTo = null;
	}

	void markVerified(LocalDate on) {
		this.verifiedOn = on;
	}

}
