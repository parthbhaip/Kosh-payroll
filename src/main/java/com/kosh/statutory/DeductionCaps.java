package com.kosh.statutory;

import java.math.BigDecimal;
import java.util.Map;

import jakarta.persistence.Embeddable;

/**
 * The most each kind of declared deduction may take off taxable income under the old regime, per tax year
 * (PAYROLL-RULES section 8.2). Named by what the deduction is for, not by section numbers, which changed under the
 * Income-tax Act, 2025.
 */
@Embeddable
public record DeductionCaps(BigDecimal investments, BigDecimal healthInsurance, BigDecimal homeLoanInterest)
		implements Rules {

	public DeductionCaps {
		investments = Scales.money(investments);
		healthInsurance = Scales.money(healthInsurance);
		homeLoanInterest = Scales.money(homeLoanInterest);
	}

	@Override
	public Map<String, BigDecimal> values() {
		return Map.of("investments", investments, "healthInsurance", healthInsurance, "homeLoanInterest",
				homeLoanInterest);
	}

}
