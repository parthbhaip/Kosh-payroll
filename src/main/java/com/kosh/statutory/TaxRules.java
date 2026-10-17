package com.kosh.statutory;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One income-tax regime for a tax year (PAYROLL-RULES section 8.1): slabs, the standard deduction on salary, the
 * rebate for total income up to {@code rebateIncomeLimit} (at most {@code rebateMax}), surcharges and the cess (per cent
 * of tax plus surcharge). {@code employerNpsRate}: employer NPS is deductible up to this per cent of Basic + DA; null
 * when the regime allows no such deduction in Kosh. Slabs and surcharges are kept lowest first.
 */
public record TaxRules(BigDecimal standardDeduction, BigDecimal rebateIncomeLimit, BigDecimal rebateMax,
		BigDecimal cessRate, BigDecimal employerNpsRate, List<TaxSlab> slabs, List<Surcharge> surcharges)
		implements Rules {

	public TaxRules {
		standardDeduction = Scales.money(standardDeduction);
		rebateIncomeLimit = Scales.money(rebateIncomeLimit);
		rebateMax = Scales.money(rebateMax);
		cessRate = Scales.rate(cessRate);
		employerNpsRate = Scales.rate(employerNpsRate);
		slabs = slabs.stream()
			.sorted(Comparator.comparing(TaxSlab::upTo, Comparator.nullsLast(Comparator.naturalOrder())))
			.toList();
		surcharges = surcharges.stream().sorted(Comparator.comparing(Surcharge::incomeAbove)).toList();
	}

	@Override
	public Map<String, BigDecimal> values() {
		Map<String, BigDecimal> values = new HashMap<>();
		values.put("standardDeduction", standardDeduction);
		values.put("rebateIncomeLimit", rebateIncomeLimit);
		values.put("rebateMax", rebateMax);
		values.put("cessRate", cessRate);
		values.put("employerNpsRate", employerNpsRate);
		return values;
	}

}
