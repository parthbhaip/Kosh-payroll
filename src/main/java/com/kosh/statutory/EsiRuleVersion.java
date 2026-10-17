package com.kosh.statutory;

import java.time.LocalDate;

import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** A version of the {@link RuleFamily#ESI} rules; its values are the columns of {@code esi_rules}. */
@Entity
@Table(name = "esi_rules")
public class EsiRuleVersion extends RuleVersion {

	@Embedded
	private EsiRules rules;

	protected EsiRuleVersion() {
	}

	EsiRuleVersion(EsiRules rules, LocalDate effectiveFrom, String sourceNote, LocalDate verifiedOn) {
		super(RuleFamily.ESI, NATIONAL, effectiveFrom, sourceNote, verifiedOn);
		this.rules = rules;
	}

	@Override
	public EsiRules rules() {
		return rules;
	}

}
