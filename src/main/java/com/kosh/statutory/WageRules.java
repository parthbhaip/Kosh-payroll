package com.kosh.statutory;

import java.math.BigDecimal;
import java.util.Map;

import jakarta.persistence.Embeddable;

/**
 * Wages under the Code on Wages (PAYROLL-RULES section 3): pay excluded from wages above {@code excludedCapRate} per cent
 * of total pay is added back. Full and final wages are due {@code settlementDueWorkingDays} working days after the last
 * working day (section 13).
 */
@Embeddable
public record WageRules(BigDecimal excludedCapRate, int settlementDueWorkingDays) implements Rules {

	public WageRules {
		excludedCapRate = Scales.rate(excludedCapRate);
	}

	@Override
	public Map<String, BigDecimal> values() {
		return Map.of("excludedCapRate", excludedCapRate, "settlementDueWorkingDays",
				Scales.whole(settlementDueWorkingDays));
	}

}
