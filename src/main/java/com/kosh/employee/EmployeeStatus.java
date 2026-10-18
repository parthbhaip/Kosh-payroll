package com.kosh.employee;

/**
 * Where someone is in their time with the company: working, serving notice (separation started, T52) or gone (full and
 * final settled, T53). Stored by name.
 */
public enum EmployeeStatus {

	ACTIVE("Active"), ON_NOTICE("On notice"), EXITED("Exited");

	private final String label;

	EmployeeStatus(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

}
