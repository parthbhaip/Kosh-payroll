package com.kosh.statutory;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.kosh.common.Gender;

/**
 * One state's professional tax (PAYROLL-RULES section 7): slabs by gender and gross monthly salary, and the most an
 * employee pays in a tax year. Slabs are kept by gender, then upper limit, the open-ended top slab last.
 */
public record PtRules(BigDecimal annualCap, List<PtSlab> slabs) implements Rules {

	private static final Comparator<PtSlab> ORDER = Comparator.comparing(PtSlab::gender)
		.thenComparing(PtSlab::upTo, Comparator.nullsLast(Comparator.naturalOrder()));

	public PtRules {
		annualCap = Scales.money(annualCap);
		slabs = slabs.stream().sorted(ORDER).toList();
	}

	/** The slabs for one gender, lowest first. */
	public List<PtSlab> slabs(Gender gender) {
		return slabs.stream().filter(slab -> slab.gender() == gender).toList();
	}

	/** The slab that a gross monthly salary falls in. */
	public PtSlab slabFor(Gender gender, BigDecimal monthlySalary) {
		return slabs(gender).stream()
			.filter(slab -> slab.upTo() == null || monthlySalary.compareTo(slab.upTo()) <= 0)
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("No professional tax slab for " + gender));
	}

	@Override
	public Map<String, BigDecimal> values() {
		return Map.of("annualCap", annualCap);
	}

}
