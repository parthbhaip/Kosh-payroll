package com.kosh.statutory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import jakarta.persistence.Embeddable;

/**
 * Employees' State Insurance (PAYROLL-RULES section 6). Covered when fixed monthly gross is at most
 * {@code wageCeiling} ({@code disabilityWageCeiling} for a person with disability); no employee share when the average
 * daily wage is at most {@code exemptDailyWage}. The year has two contribution periods of six months, starting in
 * {@code periodStartMonth} (1-6) and six months later.
 */
@Embeddable
public record EsiRules(BigDecimal wageCeiling, BigDecimal disabilityWageCeiling, BigDecimal employeeRate,
		BigDecimal employerRate, BigDecimal exemptDailyWage, int periodStartMonth) implements Rules {

	/** Months in a contribution period. */
	public static final int PERIOD_MONTHS = 6;

	public EsiRules {
		wageCeiling = Scales.money(wageCeiling);
		disabilityWageCeiling = Scales.money(disabilityWageCeiling);
		employeeRate = Scales.rate(employeeRate);
		employerRate = Scales.rate(employerRate);
		exemptDailyWage = Scales.money(exemptDailyWage);
	}

	/** The first day of the contribution period that {@code date} falls in (1 Apr or 1 Oct with the seed). */
	public LocalDate periodStart(LocalDate date) {
		int monthsIn = Math.floorMod(date.getMonthValue() - periodStartMonth, PERIOD_MONTHS);
		return date.withDayOfMonth(1).minusMonths(monthsIn);
	}

	@Override
	public Map<String, BigDecimal> values() {
		return Map.of("wageCeiling", wageCeiling, "disabilityWageCeiling", disabilityWageCeiling, "employeeRate",
				employeeRate, "employerRate", employerRate, "exemptDailyWage", exemptDailyWage, "periodStartMonth",
				Scales.whole(periodStartMonth));
	}

}
