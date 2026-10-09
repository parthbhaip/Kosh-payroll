package com.kosh.statutory;

/**
 * How Provident Fund is worked out for an employee (PAYROLL-RULES section 5). The company setting picks the default
 * for new employees, which is RESTRICT or ACTUAL; EXCLUDED is set per employee and only for someone who may opt out.
 */
public enum PfPolicy {

	RESTRICT("Restrict to the wage ceiling",
			"Employee and employer PF on PF wages up to the statutory ceiling. The usual choice."),
	ACTUAL("Actual PF wages", "Employee and employer PF on full PF wages, even above the ceiling."),
	EXCLUDED("Not a PF member",
			"No PF. Only for a joiner above the ceiling who has never been a PF member and has no UAN.");

	private final String label;

	private final String description;

	PfPolicy(String label, String description) {
		this.label = label;
		this.description = description;
	}

	public String label() {
		return label;
	}

	public String description() {
		return description;
	}

}
