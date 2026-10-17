package com.kosh.statutory;

import java.math.BigDecimal;
import java.util.Map;

/**
 * The values of one rule family in one version (PLAN T14). Immutable. {@link #values()} gives the single numbers by
 * the keys of {@link RuleFamily#fields()}; slab rows are on the records that have them.
 */
public sealed interface Rules
		permits WageRules, PfRules, EsiRules, PtRules, TaxRules, DeductionCaps, GratuityRules {

	/** Each single value by its field key ({@link RuleField#key()}). */
	Map<String, BigDecimal> values();

}
