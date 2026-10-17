package com.kosh.statutory;

import java.math.BigDecimal;
import java.util.Map;

import jakarta.persistence.Embeddable;

/**
 * Gratuity (PAYROLL-RULES section 13): wages x {@code daysPerYear} / {@code monthDays} x years of service, where a
 * final part-year of more than {@code partYearMonths} months counts as a year; payable after {@code eligibilityYears}
 * of continuous service ({@code fixedTermEligibilityYears} for fixed-term employees); at most {@code maxAmount}.
 */
@Embeddable
public record GratuityRules(int eligibilityYears, int fixedTermEligibilityYears, int daysPerYear, int monthDays,
		int partYearMonths, BigDecimal maxAmount) implements Rules {

	public GratuityRules {
		maxAmount = Scales.money(maxAmount);
	}

	@Override
	public Map<String, BigDecimal> values() {
		return Map.of("eligibilityYears", Scales.whole(eligibilityYears), "fixedTermEligibilityYears",
				Scales.whole(fixedTermEligibilityYears), "daysPerYear", Scales.whole(daysPerYear), "monthDays",
				Scales.whole(monthDays), "partYearMonths", Scales.whole(partYearMonths), "maxAmount", maxAmount);
	}

}
