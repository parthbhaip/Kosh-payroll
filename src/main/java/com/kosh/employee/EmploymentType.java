package com.kosh.employee;

/**
 * The terms someone is employed on. A fixed-term employee always has a contract end date and an intern may have one;
 * permanent staff and people on probation never do. Stored by name.
 */
public enum EmploymentType {

	PERMANENT("Permanent"), PROBATION("On probation"), FIXED_TERM("Fixed term"), INTERN("Intern");

	private final String label;

	EmploymentType(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

	/** Whether a contract end date is required. */
	public boolean needsEndDate() {
		return this == FIXED_TERM;
	}

	/** Whether a contract end date may be given at all. */
	public boolean allowsEndDate() {
		return this == FIXED_TERM || this == INTERN;
	}

}
