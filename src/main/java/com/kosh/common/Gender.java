package com.kosh.common;

import java.util.List;

/**
 * Gender of a person, with the three options EPFO and ESIC records use. Stored by name. Maharashtra professional tax
 * keeps its slabs for men and for women only; {@link #ptSlabGender()} says whose slabs apply (PAYROLL-RULES section
 * 15).
 */
public enum Gender {

	MALE("Male"), FEMALE("Female"), TRANSGENDER("Transgender");

	/** The genders professional tax slabs are kept for. */
	public static final List<Gender> PT_SLAB_GENDERS = List.of(MALE, FEMALE);

	private final String label;

	Gender(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

	/** The women's slabs are a concession for women, so everyone else pays on the general (men's) slabs. */
	public Gender ptSlabGender() {
		return (this == FEMALE) ? FEMALE : MALE;
	}

}
