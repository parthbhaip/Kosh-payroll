package com.kosh.employee;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.kosh.user.Role;

/**
 * The tabs of an employee's profile (PLAN T16), in display order. Overview sits at {@code /hr/employees/{id}}, each
 * other tab at {@code /hr/employees/{id}/{slug}}. The Salary tab exists only for the salary roles.
 */
public enum ProfileTab {

	OVERVIEW("Overview", ""), JOB("Job", "job"), SALARY("Salary", "salary"), STATUTORY("Statutory", "statutory"),
	BANK("Bank", "bank"), DOCUMENTS("Documents", "documents"), HISTORY("History", "history");

	private final String label;

	private final String slug;

	ProfileTab(String label, String slug) {
		this.label = label;
		this.slug = slug;
	}

	public String label() {
		return label;
	}

	/** The path below the profile: empty for the overview, else {@code /job}, {@code /salary} and so on. */
	public String path() {
		return slug.isEmpty() ? "" : "/" + slug;
	}

	public boolean visibleTo(Collection<Role> roles) {
		return this != SALARY || Role.seesSalaries(roles);
	}

	/** The tabs a user with these roles sees, in order. */
	public static List<ProfileTab> visibleTabs(Collection<Role> roles) {
		return Arrays.stream(values()).filter(tab -> tab.visibleTo(roles)).toList();
	}

	/** The tab at {@code /hr/employees/{id}/{slug}}; the overview has no slug of its own. */
	public static Optional<ProfileTab> fromSlug(String slug) {
		return Arrays.stream(values()).filter(tab -> !tab.slug.isEmpty() && tab.slug.equals(slug)).findFirst();
	}

}
