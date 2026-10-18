package com.kosh.employee;

import java.util.List;
import java.util.Optional;

/**
 * The steps of the add and edit employee form (PLAN T17), in order. Each step owns the {@link EmployeeForm} fields it
 * shows: only those are bound from its page, and a rule broken on one of them is reported on that step.
 */
public enum EmployeeStep {

	PERSONAL("personal", "Personal", "Name, date of birth, contact details and addresses.",
			List.of("firstName", "middleName", "lastName", "gender", "dateOfBirth", "personalEmail", "mobile",
					"currentLine1", "currentLine2", "currentCity", "currentState", "currentPincode", "permanentSame",
					"permanentLine1", "permanentLine2", "permanentCity", "permanentState", "permanentPincode",
					"emergencyName", "emergencyRelation", "emergencyPhone")),

	JOB("job", "Job", "Where they work, whom they report to and on what terms.",
			List.of("departmentId", "designationId", "gradeId", "locationId", "costCentreId", "reportingManagerId",
					"joiningDate", "employmentType", "contractEndDate", "confirmationDate")),

	STATUTORY("statutory", "Statutory", "PAN, provident fund, ESI and professional tax.",
			List.of("pan", "uan", "esiIpNumber", "pfPolicy", "ptExempt", "ptExemptReason", "personWithDisability")),

	BANK("bank", "Bank", "The account salary is paid into.",
			List.of("holderName", "bankName", "ifsc", "accountNumber", "accountNumberConfirm")),

	REVIEW("review", "Review", "Check everything once more before saving.", List.of("createLogin", "loginEmail"));

	private final String slug;

	private final String label;

	private final String intro;

	private final List<String> fields;

	EmployeeStep(String slug, String label, String intro, List<String> fields) {
		this.slug = slug;
		this.label = label;
		this.intro = intro;
		this.fields = fields;
	}

	public String slug() {
		return slug;
	}

	public String label() {
		return label;
	}

	public String intro() {
		return intro;
	}

	/** The form fields this step shows, in page order. */
	public List<String> fields() {
		return fields;
	}

	/** 1 for the first step. */
	public int number() {
		return ordinal() + 1;
	}

	/** The step after this one; Review is the last. */
	public EmployeeStep next() {
		return values()[Math.min(ordinal() + 1, values().length - 1)];
	}

	/** The step before this one, or null for the first. */
	public EmployeeStep previous() {
		return (ordinal() == 0) ? null : values()[ordinal() - 1];
	}

	public static Optional<EmployeeStep> fromSlug(String slug) {
		for (EmployeeStep step : values()) {
			if (step.slug.equals(slug)) {
				return Optional.of(step);
			}
		}
		return Optional.empty();
	}

	/** The step that shows a form field, or null for a rule about the form as a whole. */
	public static EmployeeStep of(String field) {
		for (EmployeeStep step : values()) {
			if (step.fields.contains(field)) {
				return step;
			}
		}
		return null;
	}

	/** The labels of every step, for the stepper. */
	public static List<String> labels() {
		return List.of(values()).stream().map(EmployeeStep::label).toList();
	}

	/** Field order across the whole form, for listing problems in page order (unknown fields last). */
	static int order(String field) {
		int at = 0;
		for (EmployeeStep step : values()) {
			int index = step.fields.indexOf(field);
			if (index >= 0) {
				return at + index;
			}
			at += step.fields.size();
		}
		return Integer.MAX_VALUE;
	}

}
