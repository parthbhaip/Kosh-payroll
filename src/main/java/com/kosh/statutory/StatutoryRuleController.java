package com.kosh.statutory;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;

import com.kosh.common.BusinessRuleException;
import com.kosh.common.Fmt;
import com.kosh.common.Gender;
import com.kosh.common.IndianState;
import com.kosh.common.NotFoundException;
import com.kosh.statutory.StatutoryRuleService.Added;
import com.kosh.statutory.StatutoryRuleService.Draft;
import com.kosh.statutory.StatutoryRuleService.NewVersion;
import com.kosh.statutory.StatutoryRuleService.Problem;
import com.kosh.statutory.StatutoryRuleService.Withdrawn;
import com.kosh.web.Flash;

/**
 * {@code /admin/statutory}: the statutory rules in force with their sources and last-verified dates, a form to add a
 * new version of one family, "mark as checked" and withdrawing a scheduled version. ADMIN only (URL rule and service).
 * Professional tax pages take the state as {@code ?state=MH}.
 */
@Controller
@RequestMapping("/admin/statutory")
class StatutoryRuleController {

	private static final String BASE = "/admin/statutory";

	/** Empty slab rows offered below the filled ones. */
	private static final int BLANK_ROWS = 2;

	private final StatutoryRuleService service;

	private final Fmt fmt;

	private final Clock clock;

	StatutoryRuleController(StatutoryRuleService service, Fmt fmt, Clock clock) {
		this.service = service;
		this.fmt = fmt;
		this.clock = clock;
	}

	@GetMapping
	String overview(Model model) {
		model.addAttribute("families", service.overview());
		model.addAttribute("today", LocalDate.now(clock));
		model.addAttribute("checkNotice", Flash.info("Rules change: check them before a payroll run")
			.withText("Compare each value with its source. Add a new version when one has changed, or mark the rules as checked."));
		return "admin/statutory/overview";
	}

	@GetMapping("/{slug}/new")
	String newVersion(@PathVariable String slug, @RequestParam(required = false) String state, Model model) {
		RuleFamily family = family(slug);
		IndianState scope = state(state);
		Draft draft = service.draft(family, scope);
		model.addAttribute("form", VersionForm.of(family, draft, LocalDate.now(clock)));
		return formView(family, scope, draft, model);
	}

	@PostMapping("/{slug}")
	String add(@PathVariable String slug, @RequestParam(required = false) String state,
			@ModelAttribute("form") VersionForm form, BindingResult errors, Model model, RedirectAttributes redirect) {
		RuleFamily family = family(slug);
		IndianState scope = state(state);
		Draft draft = service.draft(family, scope);
		NewVersion version = form.toNewVersion(family, errors);
		for (Problem problem : service.problems(family, scope, version)) {
			if (problem.field() == null) {
				errors.reject("rule", problem.message());
			}
			else if (!errors.hasFieldErrors(problem.field() + "*")) {
				errors.rejectValue(problem.field(), "rule", problem.message());
			}
		}
		if (!errors.hasErrors()) {
			try {
				Added added = service.add(family, scope, version);
				String changed = added.changed().isEmpty() ? "" : "Changed: " + String.join(", ", added.changed()) + ". ";
				redirect.addFlashAttribute(Flash.ATTRIBUTE, Flash
					.success("New version from %s added".formatted(fmt.date(added.version().effectiveFrom())))
					.withText("%s. %sThe version from %s ends on %s.".formatted(added.version().title(), changed,
							fmt.date(added.previous().effectiveFrom()), fmt.date(added.previous().effectiveTo()))));
				return "redirect:" + BASE + "#" + family.anchor(scope);
			}
			catch (BusinessRuleException rule) {
				rule.addTo(errors);
			}
		}
		form.padRows(family);
		return formView(family, scope, draft, model);
	}

	@PostMapping("/{slug}/checked")
	String checked(@PathVariable String slug, @RequestParam(required = false) String state,
			RedirectAttributes redirect) {
		RuleFamily family = family(slug);
		IndianState scope = state(state);
		String title = family.title(scope);
		try {
			boolean marked = service.markChecked(family, scope);
			redirect.addFlashAttribute(Flash.ATTRIBUTE, marked
					? Flash.success(title + " marked as checked").withText("Last verified " + fmt.date(LocalDate.now(clock)) + ".")
					: Flash.info("Already checked today").withText(title + " was marked as checked earlier today."));
		}
		catch (BusinessRuleException rule) {
			redirect.addFlashAttribute(Flash.ATTRIBUTE, Flash.danger(rule.getMessage()));
		}
		return "redirect:" + BASE + "#" + family.anchor(scope);
	}

	/** {@code version}: the id of the scheduled version the page offered to withdraw. */
	@PostMapping("/{slug}/withdraw")
	String withdraw(@PathVariable String slug, @RequestParam(required = false) String state, @RequestParam long version,
			RedirectAttributes redirect) {
		RuleFamily family = family(slug);
		IndianState scope = state(state);
		try {
			Withdrawn withdrawn = service.withdraw(family, scope, version);
			redirect.addFlashAttribute(Flash.ATTRIBUTE, Flash
				.success("Version from %s withdrawn".formatted(fmt.date(withdrawn.version().effectiveFrom())))
				.withText("%s. The version from %s has no end date again.".formatted(withdrawn.version().title(),
						fmt.date(withdrawn.previous().effectiveFrom()))));
		}
		catch (BusinessRuleException rule) {
			redirect.addFlashAttribute(Flash.ATTRIBUTE, Flash.danger(rule.getMessage()));
		}
		return "redirect:" + BASE + "#" + family.anchor(scope);
	}

	private String formView(RuleFamily family, IndianState state, Draft draft, Model model) {
		model.addAttribute("family", family);
		model.addAttribute("latest", draft.latest());
		model.addAttribute("fields", family.fields());
		model.addAttribute("genders", Gender.values());
		model.addAttribute("anchor", family.anchor(state));
		model.addAttribute("action", UriComponentsBuilder.fromPath(BASE + "/" + family.slug())
			.queryParamIfPresent("state", Optional.ofNullable(state).map(IndianState::name))
			.build()
			.toUriString());
		Map<Integer, String> periods = new LinkedHashMap<>();
		for (int month = 1; month <= EsiRules.PERIOD_MONTHS; month++) {
			periods.put(month, StatutoryRuleService.monthName(month) + " and "
					+ StatutoryRuleService.monthName(month + EsiRules.PERIOD_MONTHS));
		}
		model.addAttribute("periodOptions", periods);
		model.addAttribute("dateHelp", (family.startsWithTaxYear() ? "1 April of a tax year after %s." : "The 1st of a month after %s.")
			.formatted(fmt.date(draft.latest().effectiveFrom())) + " The latest version ends the day before.");
		return "admin/statutory/new-version";
	}

	private static RuleFamily family(String slug) {
		return RuleFamily.bySlug(slug).orElseThrow(() -> new NotFoundException("No rule family " + slug));
	}

	/** The state of a professional tax page; an unknown code is 404. */
	private static IndianState state(String code) {
		if (code == null || code.isBlank()) {
			return null;
		}
		try {
			return IndianState.valueOf(code.strip());
		}
		catch (IllegalArgumentException unknown) {
			throw new NotFoundException("No state " + code);
		}
	}

	/** Plain decimal notation only: {@code 1E+9} would make a billion-digit number once scaled. */
	private static final Pattern DECIMAL = Pattern.compile("-?(\\d+(\\.\\d*)?|\\.\\d+)");

	/**
	 * Reads an amount or rate as typed: commas, spaces, ₹ and % are ignored; empty is null.
	 * @throws NumberFormatException for anything but a plain decimal number
	 */
	static BigDecimal number(String text) {
		if (text == null) {
			return null;
		}
		String plain = text.replace(",", "").replace("₹", "").replace("%", "").replaceAll("\\s+", "");
		if (plain.isEmpty()) {
			return null;
		}
		if (!DECIMAL.matcher(plain).matches()) {
			throw new NumberFormatException("Not a plain decimal number");
		}
		return new BigDecimal(plain);
	}

	/** The new-version form. Numbers are kept as typed, so a wrong entry is shown back as it was. */
	public static class VersionForm {

		@DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
		private LocalDate effectiveFrom;

		private Map<String, String> values = new HashMap<>();

		private List<PtSlabRow> ptSlabs = new ArrayList<>();

		private List<SlabRow> taxSlabs = new ArrayList<>();

		private List<SlabRow> surcharges = new ArrayList<>();

		private String sourceNote;

		@DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
		private LocalDate verifiedOn;

		/** Starts from the latest version's values, its source note and today as the checked date. */
		static VersionForm of(RuleFamily family, Draft draft, LocalDate today) {
			VersionForm form = new VersionForm();
			Rules rules = draft.rules();
			rules.values().forEach((key, value) -> form.values.put(key, plain(value)));
			if (rules instanceof PtRules pt) {
				pt.slabs().forEach(slab -> form.ptSlabs.add(new PtSlabRow(slab.gender().name(), plain(slab.upTo()),
						plain(slab.monthly()), plain(slab.february()))));
			}
			if (rules instanceof TaxRules tax) {
				tax.slabs().forEach(slab -> form.taxSlabs.add(new SlabRow(plain(slab.upTo()), plain(slab.rate()))));
				tax.surcharges()
					.forEach(row -> form.surcharges.add(new SlabRow(plain(row.incomeAbove()), plain(row.rate()))));
			}
			form.sourceNote = draft.latest().sourceNote();
			form.verifiedOn = today;
			form.padRows(family);
			return form;
		}

		/**
		 * The typed values; a number that can't be read, or a slab row with a missing value, is marked on its field and
		 * left out (null). Empty slab rows are dropped first, so "Row 2" means the same row on the page and in the
		 * service's messages.
		 */
		NewVersion toNewVersion(RuleFamily family, BindingResult errors) {
			Map<String, BigDecimal> parsed = new HashMap<>();
			for (RuleField field : family.fields()) {
				parsed.put(field.key(), cell(errors, StatutoryRuleService.valueField(field.key()),
						values.get(field.key()), null, hint(field)));
			}
			ptSlabs.removeIf(PtSlabRow::isBlank);
			taxSlabs.removeIf(SlabRow::isBlank);
			surcharges.removeIf(SlabRow::isBlank);
			List<PtSlab> pt = new ArrayList<>();
			List<TaxSlab> slabs = new ArrayList<>();
			List<Surcharge> extra = new ArrayList<>();
			if (family.hasPtSlabs()) {
				for (int i = 0; i < ptSlabs.size(); i++) {
					pt.add(ptSlabs.get(i).toSlab(i, errors));
				}
			}
			if (family.hasTaxSlabs()) {
				for (int i = 0; i < taxSlabs.size(); i++) {
					SlabRow row = taxSlabs.get(i);
					String at = "taxSlabs[" + i + "].";
					String rowName = "Row " + (i + 1) + ": ";
					slabs.add(new TaxSlab(
							cell(errors, at + "amount", row.amount, null,
									rowName + "enter the income limit in rupees, or leave it empty for the top slab."),
							cell(errors, at + "rate", row.rate, rowName + "enter the tax rate.",
									rowName + "enter the tax rate as a percentage, like 5.")));
				}
				for (int i = 0; i < surcharges.size(); i++) {
					SlabRow row = surcharges.get(i);
					String at = "surcharges[" + i + "].";
					String rowName = "Row " + (i + 1) + ": ";
					extra.add(new Surcharge(
							cell(errors, at + "amount", row.amount, rowName + "enter the income.",
									rowName + "enter the income in rupees, like 5000000."),
							cell(errors, at + "rate", row.rate, rowName + "enter the surcharge rate.",
									rowName + "enter the surcharge as a percentage, like 10.")));
				}
			}
			return new NewVersion(effectiveFrom, parsed, pt, slabs, extra, sourceNote, verifiedOn);
		}

		/** Drops trailing empty rows, then offers {@link #BLANK_ROWS} empty ones, up to the row limit. */
		void padRows(RuleFamily family) {
			if (family.hasPtSlabs()) {
				pad(ptSlabs, PtSlabRow::isBlank, PtSlabRow::new);
			}
			if (family.hasTaxSlabs()) {
				pad(taxSlabs, SlabRow::isBlank, SlabRow::new);
				pad(surcharges, SlabRow::isBlank, SlabRow::new);
			}
		}

		private static <T> void pad(List<T> rows, Predicate<T> blank, Supplier<T> empty) {
			while (!rows.isEmpty() && blank.test(rows.getLast())) {
				rows.removeLast();
			}
			for (int i = 0; i < BLANK_ROWS && rows.size() < StatutoryRuleService.MAX_ROWS; i++) {
				rows.add(empty.get());
			}
		}

		/**
		 * One typed number: null when empty (and {@code missing}, if given, is shown), null with {@code unreadable}
		 * shown when it is not a number.
		 */
		private static BigDecimal cell(BindingResult errors, String field, String text, String missing,
				String unreadable) {
			try {
				BigDecimal value = number(text);
				if (value == null && missing != null) {
					errors.rejectValue(field, "required", missing);
				}
				return value;
			}
			catch (NumberFormatException wrong) {
				errors.rejectValue(field, "number", unreadable);
				return null;
			}
		}

		private static String hint(RuleField field) {
			return switch (field.kind()) {
				case MONEY -> "Enter an amount in rupees, like 15000.";
				case RATE -> "Enter a percentage, like 8.33.";
				case COUNT -> "Enter a whole number.";
				case HALF_YEARS -> "Choose when the periods start.";
			};
		}

		/** {@code 15000}, {@code 8.33}: how a stored value is typed. */
		private static String plain(BigDecimal value) {
			return (value == null) ? null : value.stripTrailingZeros().toPlainString();
		}

		public LocalDate getEffectiveFrom() {
			return effectiveFrom;
		}

		public void setEffectiveFrom(LocalDate effectiveFrom) {
			this.effectiveFrom = effectiveFrom;
		}

		public Map<String, String> getValues() {
			return values;
		}

		public void setValues(Map<String, String> values) {
			this.values = (values == null) ? new HashMap<>() : values;
		}

		public List<PtSlabRow> getPtSlabs() {
			return ptSlabs;
		}

		public void setPtSlabs(List<PtSlabRow> ptSlabs) {
			this.ptSlabs = (ptSlabs == null) ? new ArrayList<>() : ptSlabs;
		}

		public List<SlabRow> getTaxSlabs() {
			return taxSlabs;
		}

		public void setTaxSlabs(List<SlabRow> taxSlabs) {
			this.taxSlabs = (taxSlabs == null) ? new ArrayList<>() : taxSlabs;
		}

		public List<SlabRow> getSurcharges() {
			return surcharges;
		}

		public void setSurcharges(List<SlabRow> surcharges) {
			this.surcharges = (surcharges == null) ? new ArrayList<>() : surcharges;
		}

		public String getSourceNote() {
			return sourceNote;
		}

		public void setSourceNote(String sourceNote) {
			this.sourceNote = sourceNote;
		}

		public LocalDate getVerifiedOn() {
			return verifiedOn;
		}

		public void setVerifiedOn(LocalDate verifiedOn) {
			this.verifiedOn = verifiedOn;
		}

	}

	/** A professional tax slab row as typed. */
	public static class PtSlabRow {

		private String gender;

		private String upTo;

		private String monthly;

		private String february;

		public PtSlabRow() {
		}

		PtSlabRow(String gender, String upTo, String monthly, String february) {
			this.gender = gender;
			this.upTo = upTo;
			this.monthly = monthly;
			this.february = february;
		}

		boolean isBlank() {
			return blank(gender) && blank(upTo) && blank(monthly) && blank(february);
		}

		PtSlab toSlab(int index, BindingResult errors) {
			String at = "ptSlabs[" + index + "].";
			String rowName = "Row " + (index + 1) + ": ";
			Gender parsed = null;
			if (blank(gender)) {
				errors.rejectValue(at + "gender", "required", rowName + "choose a gender.");
			}
			else {
				try {
					parsed = Gender.valueOf(gender.strip());
				}
				catch (IllegalArgumentException unknown) {
					errors.rejectValue(at + "gender", "rule", rowName + "choose a gender from the list.");
				}
			}
			return new PtSlab(parsed,
					VersionForm.cell(errors, at + "upTo", upTo, null,
							rowName + "enter the salary limit in rupees, or leave it empty for the top slab."),
					VersionForm.cell(errors, at + "monthly", monthly, rowName + "enter the monthly PT.",
							rowName + "enter the monthly PT in rupees, like 200."),
					VersionForm.cell(errors, at + "february", february, rowName + "enter the February PT.",
							rowName + "enter the February PT in rupees, like 300."));
		}

		public String getGender() {
			return gender;
		}

		public void setGender(String gender) {
			this.gender = gender;
		}

		public String getUpTo() {
			return upTo;
		}

		public void setUpTo(String upTo) {
			this.upTo = upTo;
		}

		public String getMonthly() {
			return monthly;
		}

		public void setMonthly(String monthly) {
			this.monthly = monthly;
		}

		public String getFebruary() {
			return february;
		}

		public void setFebruary(String february) {
			this.february = february;
		}

	}

	/** An income-tax slab (amount: income up to) or surcharge (amount: income above) row as typed. */
	public static class SlabRow {

		private String amount;

		private String rate;

		public SlabRow() {
		}

		SlabRow(String amount, String rate) {
			this.amount = amount;
			this.rate = rate;
		}

		boolean isBlank() {
			return blank(amount) && blank(rate);
		}

		public String getAmount() {
			return amount;
		}

		public void setAmount(String amount) {
			this.amount = amount;
		}

		public String getRate() {
			return rate;
		}

		public void setRate(String rate) {
			this.rate = rate;
		}

	}

	private static boolean blank(String text) {
		return text == null || text.isBlank();
	}

}
