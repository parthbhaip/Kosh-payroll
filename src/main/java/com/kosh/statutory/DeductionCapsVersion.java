package com.kosh.statutory;

import java.time.LocalDate;

import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** A version of the {@link RuleFamily#OLD_TAX_CAPS} rules; its values are the columns of {@code tax_deduction_caps}. */
@Entity
@Table(name = "tax_deduction_caps")
public class DeductionCapsVersion extends RuleVersion {

	@Embedded
	private DeductionCaps rules;

	protected DeductionCapsVersion() {
	}

	DeductionCapsVersion(DeductionCaps rules, LocalDate effectiveFrom, String sourceNote, LocalDate verifiedOn) {
		super(RuleFamily.OLD_TAX_CAPS, NATIONAL, effectiveFrom, sourceNote, verifiedOn);
		this.rules = rules;
	}

	@Override
	public DeductionCaps rules() {
		return rules;
	}

}
