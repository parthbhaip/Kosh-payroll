package com.kosh.company;

/** How a prorated salary component is divided for a part month (PAYROLL-RULES section 2). Company setting. */
public enum ProrationMethod {

	CALENDAR_DAYS("Calendar days", "Monthly amount × paid days ÷ days in the month (28 to 31). The default."),
	FIXED_30("Fixed 30 days", "Monthly amount × paid days ÷ 30, whatever the length of the month."),
	WORKING_DAYS("Working days",
			"Monthly amount × paid days ÷ working days in the month, from the location’s holiday calendar.");

	private final String label;

	private final String description;

	ProrationMethod(String label, String description) {
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
