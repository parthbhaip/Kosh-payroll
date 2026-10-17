package com.kosh.statutory;

import java.time.LocalDate;

import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** A version of the {@link RuleFamily#PF} rules; its values are the columns of {@code pf_rules}. */
@Entity
@Table(name = "pf_rules")
public class PfRuleVersion extends RuleVersion {

	@Embedded
	private PfRules rules;

	protected PfRuleVersion() {
	}

	PfRuleVersion(PfRules rules, LocalDate effectiveFrom, String sourceNote, LocalDate verifiedOn) {
		super(RuleFamily.PF, NATIONAL, effectiveFrom, sourceNote, verifiedOn);
		this.rules = rules;
	}

	@Override
	public PfRules rules() {
		return rules;
	}

}
