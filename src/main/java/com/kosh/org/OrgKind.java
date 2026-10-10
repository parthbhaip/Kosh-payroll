package com.kosh.org;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * The five parts of the organisation structure, each a tab under {@code /hr/org/{slug}}. {@code label} is also the
 * entity type in the audit log; {@code noun} is the label in running text.
 */
public enum OrgKind {

	DEPARTMENTS("departments", "Department", "Departments", "department", "PROD", Department::new,
			"The teams people belong to, each with an optional head.",
			"Every employee belongs to one department. The payroll register and cost reports group people by it.",
			"Add the departments people work in, such as Production or Accounts."),
	DESIGNATIONS("designations", "Designation", "Designations", "designation", "OPR", Designation::new,
			"Job titles, such as Machine Operator or Accounts Executive.",
			"Each employee has one designation, printed under their name on payslips and salary letters.",
			"Add the job titles you use, such as Machine Operator or Accounts Executive."),
	GRADES("grades", "Grade", "Grades", "grade", "G3", Grade::new,
			"Levels that group similar jobs, such as trainees, operators and managers.",
			"Each employee has one grade, so pay can be compared across jobs of the same level.",
			"Add the grades you use to group jobs of a similar level."),
	COST_CENTRES("cost-centres", "Cost centre", "Cost centres", "cost centre", "CC-110", CostCentre::new,
			"Where salary cost is booked in the accounts, such as a plant or a sales office.",
			"Each employee’s salary cost is booked to one cost centre in the salary journal.",
			"Add the cost centres your accounts book salary cost to."),
	LOCATIONS("locations", "Work location", "Work locations", "work location", "SHR", WorkLocation::new,
			"The places people work. A location’s state decides which professional tax slabs apply.",
			"Each employee works at one location. Its state picks the professional tax slabs.",
			"Add the factory, offices and other places people work.");

	private final String slug;

	private final String label;

	private final String plural;

	private final String noun;

	private final String codeExample;

	private final Supplier<OrgItem> factory;

	private final String intro;

	private final String usage;

	private final String emptyText;

	OrgKind(String slug, String label, String plural, String noun, String codeExample, Supplier<OrgItem> factory,
			String intro, String usage, String emptyText) {
		this.slug = slug;
		this.label = label;
		this.plural = plural;
		this.noun = noun;
		this.codeExample = codeExample;
		this.factory = factory;
		this.intro = intro;
		this.usage = usage;
		this.emptyText = emptyText;
	}

	/** The kind whose URL segment is {@code slug}. */
	public static Optional<OrgKind> ofSlug(String slug) {
		for (OrgKind kind : values()) {
			if (kind.slug.equals(slug)) {
				return Optional.of(kind);
			}
		}
		return Optional.empty();
	}

	OrgItem newItem() {
		return factory.get();
	}

	public String slug() {
		return slug;
	}

	public String label() {
		return label;
	}

	public String plural() {
		return plural;
	}

	public String noun() {
		return noun;
	}

	public String codeExample() {
		return codeExample;
	}

	public String intro() {
		return intro;
	}

	public String usage() {
		return usage;
	}

	public String emptyText() {
		return emptyText;
	}

}
