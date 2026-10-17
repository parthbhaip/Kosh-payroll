package com.kosh.statutory;

import java.time.LocalDate;

import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** A version of the {@link RuleFamily#GRATUITY} rules; its values are the columns of {@code gratuity_rules}. */
@Entity
@Table(name = "gratuity_rules")
public class GratuityRuleVersion extends RuleVersion {

	@Embedded
	private GratuityRules rules;

	protected GratuityRuleVersion() {
	}

	GratuityRuleVersion(GratuityRules rules, LocalDate effectiveFrom, String sourceNote, LocalDate verifiedOn) {
		super(RuleFamily.GRATUITY, NATIONAL, effectiveFrom, sourceNote, verifiedOn);
		this.rules = rules;
	}

	@Override
	public GratuityRules rules() {
		return rules;
	}

}
