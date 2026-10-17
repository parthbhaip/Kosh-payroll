package com.kosh.statutory;

import java.time.LocalDate;

import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** A version of the {@link RuleFamily#WAGES} rules; its values are the columns of {@code wage_rules}. */
@Entity
@Table(name = "wage_rules")
public class WageRuleVersion extends RuleVersion {

	@Embedded
	private WageRules rules;

	protected WageRuleVersion() {
	}

	WageRuleVersion(WageRules rules, LocalDate effectiveFrom, String sourceNote, LocalDate verifiedOn) {
		super(RuleFamily.WAGES, NATIONAL, effectiveFrom, sourceNote, verifiedOn);
		this.rules = rules;
	}

	@Override
	public WageRules rules() {
		return rules;
	}

}
