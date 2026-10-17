package com.kosh.statutory;

import static com.kosh.statutory.RuleField.count;
import static com.kosh.statutory.RuleField.halfYears;
import static com.kosh.statutory.RuleField.money;
import static com.kosh.statutory.RuleField.rate;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.kosh.common.IndianState;

/**
 * The groups of statutory rules that change together and are versioned on their own (PLAN T14), in the order the
 * rules page lists them. Professional tax is kept per state; every other family applies across India. Income-tax
 * families change only from 1 April, the start of a tax year. Stored by name: never rename a constant.
 */
public enum RuleFamily {

	WAGES("wages", "Wages under the labour codes",
			"What counts as wages for PF and gratuity, and when full and final pay is due.", false,
			List.of(rate("excludedCapRate", "Cap on excluded pay",
					"HRA, conveyance, special allowance and other excluded pay above this share of total pay counts as wages."),
					count("settlementDueWorkingDays", "Full and final due within", "working days", 0, 30,
							"After the last working day."))),

	PF("pf", "Provident Fund", "Employee and employer shares, pension (EPS), EDLI and admin charges.", false,
			List.of(money("wageCeiling", "Wage ceiling",
					"PF wages above this are not counted when PF is restricted to the ceiling."),
					rate("employeeRate", "Employee share", "Of PF wages."),
					rate("employerRate", "Employer share", "Of PF wages. Pension (EPS) plus EPF."),
					rate("epsRate", "Pension (EPS)", "Part of the employer share, on PF wages up to the ceiling."),
					count("epsAgeLimit", "No pension from age", "years", 18, 100,
							"From this age the whole employer share goes to EPF."),
					rate("edliRate", "EDLI", "Employer, on PF wages up to the ceiling."),
					rate("adminRate", "EPF admin charges", "Employer, on EPF wages."),
					money("adminMinimum", "Minimum admin charges", "Per establishment per month."),
					rate("edliAdminRate", "EDLI admin charges", "Employer, on PF wages up to the ceiling."))),

	ESI("esi", "Employees’ State Insurance", "Who is covered and the contribution rates.", false,
			List.of(money("wageCeiling", "Coverage ceiling", "Covered when fixed gross monthly wages are at most this."),
					money("disabilityWageCeiling", "Ceiling for persons with disability", null),
					rate("employeeRate", "Employee share", "Of ESI wages."),
					rate("employerRate", "Employer share", "Of ESI wages."),
					money("exemptDailyWage", "No employee share up to",
							"Average daily wage. The employer share is still paid."),
					halfYears("periodStartMonth", "Contribution periods",
							"Six months each. Someone covered at the start stays covered to the end of the period."))),

	PT("pt", "Professional tax", "Monthly slabs by gender and gross salary, set by each state.", false,
			List.of(money("annualCap", "Annual maximum", "Per employee in a tax year."))),

	NEW_TAX("new-regime", "Income tax: new regime", "The default regime: slabs, rebate, surcharge and cess.", true,
			List.of(money("standardDeduction", "Standard deduction", "On salary income."),
					money("rebateIncomeLimit", "Rebate for total income up to", null),
					money("rebateMax", "Maximum rebate", null),
					rate("cessRate", "Health and education cess", "Of tax plus surcharge."),
					rate("employerNpsRate", "Employer NPS deduction", "Up to this share of Basic + DA."))),

	OLD_TAX("old-regime", "Income tax: old regime", "For employees who choose it. Slabs for people below 60.", true,
			List.of(money("standardDeduction", "Standard deduction", "On salary income."),
					money("rebateIncomeLimit", "Rebate for total income up to", null),
					money("rebateMax", "Maximum rebate", null),
					rate("cessRate", "Health and education cess", "Of tax plus surcharge."))),

	OLD_TAX_CAPS("old-regime-caps", "Old-regime deduction caps",
			"The most each declared deduction can take off taxable income in a tax year.", true,
			List.of(money("investments", "Investments", "PF, PPF, ELSS, life insurance and similar (old section 80C)."),
					money("healthInsurance", "Health insurance", "For self and family below 60 (old section 80D)."),
					money("homeLoanInterest", "Home-loan interest", "On a self-occupied house."))),

	GRATUITY("gratuity", "Gratuity", "Who is eligible, how it is worked out and the most that is paid.", false,
			List.of(count("eligibilityYears", "Eligible after", "years", 0, 40, "Of continuous service."),
					count("fixedTermEligibilityYears", "Fixed-term employees eligible after", "years", 0, 40, null),
					count("daysPerYear", "Days of wages per year of service", "days", 1, 31, null),
					count("monthDays", "Days in a month for the formula", "days", 1, 31,
							"Gratuity = wages × days per year ÷ this × years of service."),
					count("partYearMonths", "A final part-year counts above", "months", 0, 11,
							"A final part-year longer than this counts as a full year."),
					money("maxAmount", "Ceiling", "The most paid to one employee.")));

	private final String slug;

	private final String label;

	private final String summary;

	private final boolean taxYear;

	private final List<RuleField> fields;

	RuleFamily(String slug, String label, String summary, boolean taxYear, List<RuleField> fields) {
		this.slug = slug;
		this.label = label;
		this.summary = summary;
		this.taxYear = taxYear;
		this.fields = fields;
	}

	/** The family's part of the rules page address, {@code /admin/statutory/pf/new}. */
	public String slug() {
		return slug;
	}

	public String label() {
		return label;
	}

	public String summary() {
		return summary;
	}

	/** "Provident Fund", "Professional tax, Maharashtra". {@code state} only for {@link #perState()} families. */
	public String title(IndianState state) {
		return (state == null) ? label : label + ", " + state.label();
	}

	/** The family's card id on the rules page: {@code pf}, {@code pt-mh}. */
	public String anchor(IndianState state) {
		return (state == null) ? slug : slug + "-" + state.name().toLowerCase(Locale.ROOT);
	}

	/** New versions start on 1 April: the values apply to a whole tax year. */
	public boolean startsWithTaxYear() {
		return taxYear;
	}

	/** Kept per state rather than across India. */
	public boolean perState() {
		return this == PT;
	}

	public boolean hasPtSlabs() {
		return this == PT;
	}

	public boolean hasTaxSlabs() {
		return this == NEW_TAX || this == OLD_TAX;
	}

	/** The single values, in page order. */
	public List<RuleField> fields() {
		return fields;
	}

	public static Optional<RuleFamily> bySlug(String slug) {
		return Arrays.stream(values()).filter(family -> family.slug.equals(slug)).findFirst();
	}

}
