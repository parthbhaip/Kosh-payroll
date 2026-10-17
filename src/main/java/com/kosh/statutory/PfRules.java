package com.kosh.statutory;

import java.math.BigDecimal;
import java.util.Map;

import jakarta.persistence.Embeddable;

/**
 * Provident Fund (PAYROLL-RULES section 5). Rates are per cent. {@code epsRate} is part of the employer share, on PF
 * wages up to {@code wageCeiling}; nobody aged {@code epsAgeLimit} or more gets EPS. {@code adminMinimum} is the least
 * EPF admin charge per establishment per month.
 */
@Embeddable
public record PfRules(BigDecimal wageCeiling, BigDecimal employeeRate, BigDecimal employerRate, BigDecimal epsRate,
		int epsAgeLimit, BigDecimal edliRate, BigDecimal adminRate, BigDecimal adminMinimum, BigDecimal edliAdminRate)
		implements Rules {

	public PfRules {
		wageCeiling = Scales.money(wageCeiling);
		employeeRate = Scales.rate(employeeRate);
		employerRate = Scales.rate(employerRate);
		epsRate = Scales.rate(epsRate);
		edliRate = Scales.rate(edliRate);
		adminRate = Scales.rate(adminRate);
		adminMinimum = Scales.money(adminMinimum);
		edliAdminRate = Scales.rate(edliAdminRate);
	}

	@Override
	public Map<String, BigDecimal> values() {
		return Map.of("wageCeiling", wageCeiling, "employeeRate", employeeRate, "employerRate", employerRate,
				"epsRate", epsRate, "epsAgeLimit", Scales.whole(epsAgeLimit), "edliRate", edliRate, "adminRate",
				adminRate, "adminMinimum", adminMinimum, "edliAdminRate", edliAdminRate);
	}

}
