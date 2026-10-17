package com.kosh.common;

/**
 * Gender as payroll rules use it: Maharashtra professional tax has separate slabs for women. Stored by name. Adding a
 * constant later needs a decision on which professional tax slabs apply (PAYROLL-RULES section 15).
 */
public enum Gender {

	MALE("Male"), FEMALE("Female");

	private final String label;

	Gender(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

}
