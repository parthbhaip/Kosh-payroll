package com.kosh.statutory;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.kosh.common.IndianState;

/**
 * Every statutory rule in force on {@code date}, as one immutable value for the payroll calculators
 * ({@link StatutoryRuleService#forDate}). Professional tax is by state: a state without rules has none.
 * {@code versions} names the version of each family used, for payslip snapshots.
 */
public record StatutoryRules(LocalDate date, WageRules wages, PfRules pf, EsiRules esi,
		Map<IndianState, PtRules> professionalTax, TaxRules newRegime, TaxRules oldRegime,
		DeductionCaps oldRegimeCaps, GratuityRules gratuity, List<VersionInfo> versions) {

	public StatutoryRules {
		professionalTax = Map.copyOf(professionalTax);
		versions = List.copyOf(versions);
	}

	/** The state's professional tax, if the state levies one in Kosh. */
	public Optional<PtRules> pt(IndianState state) {
		return Optional.ofNullable(professionalTax.get(state));
	}

	public TaxRules tax(TaxRegime regime) {
		return (regime == TaxRegime.NEW) ? newRegime : oldRegime;
	}

}
