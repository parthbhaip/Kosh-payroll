package com.kosh.statutory;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kosh.audit.AuditAction;
import com.kosh.audit.AuditChanges;
import com.kosh.audit.AuditService;
import com.kosh.common.BusinessRuleException;
import com.kosh.common.Fmt;
import com.kosh.common.Gender;
import com.kosh.common.IndianState;
import com.kosh.common.NotFoundException;
import com.kosh.common.Text;

/**
 * Statutory rules as effective-dated versions (PLAN T14). Payroll code takes {@link #forDate} (open to any code); the
 * rules page and its new-version form are ADMIN only. A new version starts on the 1st of a month (1 April for income
 * tax) after the family's latest version, which is closed the day before. Values are never edited; a latest version
 * that has not started yet can be withdrawn, which opens the one before it again. Every new version, withdrawal and
 * "checked" mark goes in the audit log.
 */
@Service
public class StatutoryRuleService {

	/** Most slab, or surcharge, rows in one version. */
	public static final int MAX_ROWS = 12;

	/** New versions start before 1 January of the following year. */
	public static final int LAST_YEAR = 2099;

	private static final String ADMIN = "hasRole('ADMIN')";

	/** The largest amount a {@code DECIMAL(12,2)} column holds. */
	private static final BigDecimal MAX_MONEY = new BigDecimal("9999999999.99");

	private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

	private static final String RACE = "Someone changed these rules a moment ago. Check the rules page and try again.";

	/**
	 * What an admin enters for a new version. {@code values} holds the single values by {@link RuleField#key()} (null:
	 * not entered); the slab lists are read only for the families that have them.
	 */
	public record NewVersion(LocalDate effectiveFrom, Map<String, BigDecimal> values, List<PtSlab> ptSlabs,
			List<TaxSlab> taxSlabs, List<Surcharge> surcharges, String sourceNote, LocalDate verifiedOn) {

		public NewVersion {
			values = (values == null) ? Map.of() : Collections.unmodifiableMap(new HashMap<>(values));
			ptSlabs = (ptSlabs == null) ? List.of() : List.copyOf(ptSlabs);
			taxSlabs = (taxSlabs == null) ? List.of() : List.copyOf(taxSlabs);
			surcharges = (surcharges == null) ? List.of() : List.copyOf(surcharges);
			sourceNote = Text.clean(sourceNote);
		}

	}

	/** A rule a {@link NewVersion} breaks, for one form field (null: the form as a whole). */
	public record Problem(String field, String message) {

	}

	/** A single value as the rules page shows it. */
	public record Line(String label, String value, String help) {

	}

	/** Slab rows as text; the first {@code textColumns} columns are words, the rest figures. */
	public record Table(String title, String rowLabel, List<String> headers, int textColumns, List<List<String>> rows) {

	}

	/**
	 * One family on the rules page: the version in force today (or, before the first one starts, that one), its values,
	 * the next version if one is scheduled with the labels of what it changes, how many versions came before, and the
	 * latest version when it can still be withdrawn (it has not started and is not the only one; else null).
	 */
	public record FamilyView(VersionInfo current, boolean inForce, List<Line> lines, List<Table> tables,
			VersionInfo next, List<String> nextChanges, int earlierVersions, VersionInfo withdrawable) {

		/** The card's id on the page: {@code pf}, {@code pt-mh}. */
		public String anchor() {
			return current.anchor();
		}

	}

	/** The latest version of a family, to start a new one from. */
	public record Draft(VersionInfo latest, Rules rules) {

	}

	/** A version just added, the one it closed and the labels of the values that changed. */
	public record Added(VersionInfo version, VersionInfo previous, List<String> changed) {

	}

	/** A version just withdrawn and the one before it, open-ended again. */
	public record Withdrawn(VersionInfo version, VersionInfo previous) {

	}

	private final RuleVersionRepository versions;

	private final AuditService audit;

	private final Fmt fmt;

	private final Clock clock;

	StatutoryRuleService(RuleVersionRepository versions, AuditService audit, Fmt fmt, Clock clock) {
		this.versions = versions;
		this.audit = audit;
		this.fmt = fmt;
		this.clock = clock;
	}

	// ------------------------------------------------------------------ for payroll

	/**
	 * Every rule in force on {@code date}, as one immutable snapshot.
	 * @throws BusinessRuleException when a family has no version in force on that date
	 * @throws IllegalStateException when two versions of one family and scope are in force on that date (a broken
	 * timeline, for example a migration that inserted a version without closing the one before it)
	 */
	@Transactional(readOnly = true)
	public StatutoryRules forDate(LocalDate date) {
		Map<RuleFamily, RuleVersion> national = new EnumMap<>(RuleFamily.class);
		Map<IndianState, PtRules> pt = new EnumMap<>(IndianState.class);
		List<VersionInfo> used = new ArrayList<>();
		for (RuleVersion version : versions.inForceOn(date)) {
			VersionInfo info = VersionInfo.of(version);
			used.add(info);
			boolean twice = version.getFamily().perState() ? pt.put(info.state(), (PtRules) version.rules()) != null
					: national.put(version.getFamily(), version) != null;
			if (twice) {
				throw new IllegalStateException("Two versions of %s are in force on %s: %s. Close one of them."
					.formatted(info.title(), date, used.stream()
						.filter(other -> other.family() == info.family() && other.state() == info.state())
						.map(other -> "id " + other.id())
						.collect(Collectors.joining(" and "))));
			}
		}
		List<String> missing = new ArrayList<>();
		for (RuleFamily family : RuleFamily.values()) {
			if (!family.perState() && !national.containsKey(family)) {
				missing.add(family.label());
			}
		}
		if (!missing.isEmpty()) {
			throw new BusinessRuleException(null, "No statutory rules are in force on %s for %s. Add a version under Statutory rules first."
				.formatted(fmt.date(date), String.join(", ", missing)));
		}
		used.sort(Comparator.comparing(VersionInfo::family).thenComparing(VersionInfo::state,
				Comparator.nullsFirst(Comparator.naturalOrder())));
		return new StatutoryRules(date, rules(national, RuleFamily.WAGES, WageRules.class),
				rules(national, RuleFamily.PF, PfRules.class), rules(national, RuleFamily.ESI, EsiRules.class), pt,
				rules(national, RuleFamily.NEW_TAX, TaxRules.class), rules(national, RuleFamily.OLD_TAX, TaxRules.class),
				rules(national, RuleFamily.OLD_TAX_CAPS, DeductionCaps.class),
				rules(national, RuleFamily.GRATUITY, GratuityRules.class), used);
	}

	// ------------------------------------------------------------------ ADMIN

	/** Every family on file, in page order, with the version in force today. */
	@PreAuthorize(ADMIN)
	@Transactional(readOnly = true)
	public List<FamilyView> overview() {
		LocalDate today = LocalDate.now(clock);
		Map<String, List<RuleVersion>> groups = versions.findAll(Sort.by("effectiveFrom"))
			.stream()
			.sorted(Comparator.comparing(RuleVersion::getFamily)
				.thenComparing(RuleVersion::getScope)
				.thenComparing(RuleVersion::getEffectiveFrom))
			.collect(Collectors.groupingBy(version -> version.getFamily() + ":" + version.getScope(),
					LinkedHashMap::new, Collectors.toList()));
		return groups.values().stream().map(group -> view(group, today)).toList();
	}

	/** The latest version of a family, to prefill a new one. {@code state} only for professional tax. */
	@PreAuthorize(ADMIN)
	@Transactional(readOnly = true)
	public Draft draft(RuleFamily family, IndianState state) {
		RuleVersion latest = timeline(family, state).getFirst();
		return new Draft(VersionInfo.of(latest), latest.rules());
	}

	/** Every rule {@code draft} breaks, in form order. */
	@PreAuthorize(ADMIN)
	@Transactional(readOnly = true)
	public List<Problem> problems(RuleFamily family, IndianState state, NewVersion draft) {
		return problems(family, timeline(family, state).getFirst(), draft);
	}

	/**
	 * Adds a version that starts on {@code draft.effectiveFrom()} and closes the latest one the day before.
	 * @throws BusinessRuleException when a rule is broken, the values equal the latest version's, or another admin added
	 * a version at the same time
	 */
	@PreAuthorize(ADMIN)
	@Transactional
	public Added add(RuleFamily family, IndianState state, NewVersion draft) {
		RuleVersion latest = timeline(family, state).getFirst();
		List<Problem> problems = problems(family, latest, draft);
		if (!problems.isEmpty()) {
			throw new BusinessRuleException(problems.getFirst().field(), problems.getFirst().message());
		}
		Rules rules = build(family, draft);
		if (rules.equals(latest.rules())) {
			throw new BusinessRuleException(null, ("These are the values of the version from %s. Change at least one, "
					+ "or use “Mark as checked” on the rules page to record that you checked them.")
				.formatted(fmt.date(latest.getEffectiveFrom())));
		}
		LocalDate lastDay = draft.effectiveFrom().minusDays(1);
		latest.closeOn(lastDay);
		RuleVersion created = entity(family, state, rules, draft);
		try {
			versions.saveAndFlush(created);
		}
		catch (OptimisticLockingFailureException | DataIntegrityViolationException race) {
			throw new BusinessRuleException(null, RACE);
		}
		VersionInfo added = VersionInfo.of(created);
		AuditChanges changes = changes(family, latest.rules(), rules);
		audit.record(AuditAction.STATUTORY_VERSION_ADDED, RuleVersion.AUDIT_TYPE, created.getId(),
				"Added %s from %s; the version from %s ends on %s".formatted(added.title(),
						fmt.date(added.effectiveFrom()), fmt.date(latest.getEffectiveFrom()), fmt.date(lastDay)),
				changes);
		return new Added(added, VersionInfo.of(latest), List.copyOf(changes.before().keySet()));
	}

	/**
	 * Records that the version in force today was checked against its sources today.
	 * @return false when it was already marked today
	 */
	@PreAuthorize(ADMIN)
	@Transactional
	public boolean markChecked(RuleFamily family, IndianState state) {
		LocalDate today = LocalDate.now(clock);
		RuleVersion current = timeline(family, state).stream()
			.filter(version -> version.inForceOn(today))
			.findFirst()
			.orElseThrow(() -> new BusinessRuleException(null, "No version of these rules is in force today."));
		LocalDate before = current.getVerifiedOn();
		if (today.equals(before)) {
			return false;
		}
		current.markVerified(today);
		try {
			versions.saveAndFlush(current);
		}
		catch (OptimisticLockingFailureException race) {
			throw new BusinessRuleException(null, RACE);
		}
		VersionInfo info = VersionInfo.of(current);
		audit.record(AuditAction.STATUTORY_RULES_CHECKED, RuleVersion.AUDIT_TYPE, current.getId(),
				"Checked %s (from %s) against its sources".formatted(info.title(), fmt.date(info.effectiveFrom())),
				AuditChanges.create().field("Last verified", fmt.date(before), fmt.date(today)));
		return true;
	}

	/**
	 * Deletes the latest version before it starts (a mistake in a scheduled version) and opens the one before it again.
	 * {@code versionId} is the version the admin saw as the latest.
	 * @throws BusinessRuleException when that is not the latest version any more, it has started, or it is the only one
	 */
	@PreAuthorize(ADMIN)
	@Transactional
	public Withdrawn withdraw(RuleFamily family, IndianState state, long versionId) {
		List<RuleVersion> timeline = timeline(family, state);
		RuleVersion latest = timeline.getFirst();
		if (latest.getId() != versionId) {
			throw new BusinessRuleException(null, RACE);
		}
		if (!latest.getEffectiveFrom().isAfter(LocalDate.now(clock))) {
			throw new BusinessRuleException(null, ("The version from %s has started, so it can’t be withdrawn. "
					+ "Add a new version to change its values.").formatted(fmt.date(latest.getEffectiveFrom())));
		}
		if (timeline.size() == 1) {
			throw new BusinessRuleException(null, "It is the only version of these rules, so it can’t be withdrawn.");
		}
		RuleVersion previous = timeline.get(1);
		VersionInfo withdrawn = VersionInfo.of(latest);
		previous.reopen();
		versions.delete(latest);
		try {
			versions.flush();
		}
		catch (OptimisticLockingFailureException race) {
			throw new BusinessRuleException(null, RACE);
		}
		audit.record(AuditAction.STATUTORY_VERSION_WITHDRAWN, RuleVersion.AUDIT_TYPE, withdrawn.id(),
				"Withdrew %s from %s before it started; the version from %s has no end date again".formatted(
						withdrawn.title(), fmt.date(withdrawn.effectiveFrom()), fmt.date(previous.getEffectiveFrom())),
				changes(family, latest.rules(), previous.rules()));
		return new Withdrawn(withdrawn, VersionInfo.of(previous));
	}

	// ------------------------------------------------------------------ checks

	private List<Problem> problems(RuleFamily family, RuleVersion latest, NewVersion draft) {
		List<Problem> problems = new ArrayList<>();
		String date = dateProblem(family, latest, draft.effectiveFrom());
		if (date != null) {
			problems.add(new Problem("effectiveFrom", date));
		}
		for (RuleField field : family.fields()) {
			String message = valueProblem(field, draft.values().get(field.key()));
			if (message != null) {
				problems.add(new Problem(valueField(field.key()), message));
			}
		}
		crossChecks(family, draft.values(), problems);
		if (family.hasPtSlabs()) {
			ptSlabProblems(draft.ptSlabs(), problems);
		}
		if (family.hasTaxSlabs()) {
			taxSlabProblems(draft.taxSlabs(), problems);
			surchargeProblems(draft.surcharges(), problems);
		}
		if (draft.sourceNote() == null) {
			problems.add(new Problem("sourceNote",
					"Say where these values come from, such as the notification or a trusted summary."));
		}
		else if (draft.sourceNote().length() > RuleVersion.MAX_SOURCE_NOTE) {
			problems.add(new Problem("sourceNote", "Use at most %d characters.".formatted(RuleVersion.MAX_SOURCE_NOTE)));
		}
		if (draft.verifiedOn() == null) {
			problems.add(new Problem("verifiedOn", "Enter the date you checked these values against the source."));
		}
		else if (draft.verifiedOn().isAfter(LocalDate.now(clock))) {
			problems.add(new Problem("verifiedOn", "Enter today or an earlier date."));
		}
		return problems;
	}

	/** The form field of a single value: {@code values[wageCeiling]}. */
	public static String valueField(String key) {
		return "values[" + key + "]";
	}

	private String dateProblem(RuleFamily family, RuleVersion latest, LocalDate from) {
		if (from == null) {
			return "Enter the date the new values take effect.";
		}
		if (from.getYear() > LAST_YEAR) {
			return "Choose a date before %d.".formatted(LAST_YEAR + 1);
		}
		if (family.startsWithTaxYear() && (from.getMonth() != Month.APRIL || from.getDayOfMonth() != 1)) {
			return "Choose 1 April. These rules apply to a whole tax year, April to March.";
		}
		if (from.getDayOfMonth() != 1) {
			return "Choose the 1st of a month. Payroll applies one set of rules to a whole month.";
		}
		if (!from.isAfter(latest.getEffectiveFrom())) {
			return "Choose a date after %s, when the latest version starts.".formatted(fmt.date(latest.getEffectiveFrom()));
		}
		return null;
	}

	private static String valueProblem(RuleField field, BigDecimal value) {
		return switch (field.kind()) {
			case MONEY -> (value == null) ? "Enter an amount in rupees." : moneyProblem(value);
			case RATE -> (value == null) ? "Enter a percentage." : rateProblem(value);
			case COUNT -> (value == null || decimals(value) > 0 || value.compareTo(BigDecimal.valueOf(field.min())) < 0
					|| value.compareTo(BigDecimal.valueOf(field.max())) > 0)
							? "Enter a whole number from %d to %d.".formatted(field.min(), field.max()) : null;
			case HALF_YEARS -> (value == null || decimals(value) > 0 || value.compareTo(BigDecimal.ONE) < 0
					|| value.compareTo(BigDecimal.valueOf(field.max())) > 0) ? "Choose when the periods start." : null;
		};
	}

	private static String moneyProblem(BigDecimal value) {
		if (value.signum() < 0) {
			return "Enter zero or more.";
		}
		if (decimals(value) > Scales.MONEY) {
			return "Use at most two decimals.";
		}
		if (value.compareTo(MAX_MONEY) > 0) {
			return "Enter less than ₹1,000 crore.";
		}
		return null;
	}

	private static String rateProblem(BigDecimal value) {
		if (value.signum() < 0 || value.compareTo(HUNDRED) > 0) {
			return "Enter a percentage from 0 to 100.";
		}
		if (decimals(value) > Scales.RATE) {
			return "Use at most four decimals.";
		}
		return null;
	}

	private static int decimals(BigDecimal value) {
		return Math.max(0, value.stripTrailingZeros().scale());
	}

	/** Rules between two values, checked once both are entered. */
	private static void crossChecks(RuleFamily family, Map<String, BigDecimal> values, List<Problem> problems) {
		if (family == RuleFamily.PF && compare(values, "epsRate", "employerRate") > 0) {
			problems.add(new Problem(valueField("epsRate"),
					"The pension share comes out of the employer share, so it can’t be larger."));
		}
		if (family == RuleFamily.ESI && compare(values, "disabilityWageCeiling", "wageCeiling") < 0) {
			problems.add(new Problem(valueField("disabilityWageCeiling"), "Can’t be lower than the coverage ceiling."));
		}
	}

	/** Compares two values; 0 when either is missing. */
	private static int compare(Map<String, BigDecimal> values, String key, String other) {
		BigDecimal value = values.get(key);
		BigDecimal otherValue = values.get(other);
		return (value == null || otherValue == null) ? 0 : value.compareTo(otherValue);
	}

	private void ptSlabProblems(List<PtSlab> slabs, List<Problem> problems) {
		String problem = rowsProblem(slabs, slab -> slab.gender() == null
				|| !Gender.PT_SLAB_GENDERS.contains(slab.gender()) || slab.monthly() == null || slab.february() == null,
				"Choose the gender and enter both amounts of every slab.",
				slab -> firstOf(moneyIfAny(slab.upTo()), moneyProblem(slab.monthly()), moneyProblem(slab.february())));
		if (problem == null) {
			for (Gender gender : Gender.PT_SLAB_GENDERS) {
				List<BigDecimal> limits = slabs.stream()
					.filter(slab -> slab.gender() == gender)
					.map(PtSlab::upTo)
					.toList();
				problem = limitsProblem(limits, " for " + people(gender), "Add the slabs for " + people(gender) + ".");
				if (problem != null) {
					break;
				}
			}
		}
		if (problem != null) {
			problems.add(new Problem("ptSlabs", problem));
		}
	}

	private void taxSlabProblems(List<TaxSlab> slabs, List<Problem> problems) {
		String problem = rowsProblem(slabs, slab -> slab.rate() == null, "Enter the rate of every slab.",
				slab -> firstOf(moneyIfAny(slab.upTo()), rateProblem(slab.rate())));
		if (problem == null) {
			problem = limitsProblem(slabs.stream().map(TaxSlab::upTo).toList(), "", "Add at least one slab.");
		}
		if (problem != null) {
			problems.add(new Problem("taxSlabs", problem));
		}
	}

	private void surchargeProblems(List<Surcharge> surcharges, List<Problem> problems) {
		String problem = rowsProblem(surcharges, row -> row.incomeAbove() == null || row.rate() == null,
				"Enter the income and the rate of every surcharge.",
				row -> firstOf(moneyProblem(row.incomeAbove()), rateProblem(row.rate())));
		if (problem == null) {
			List<Surcharge> sorted = surcharges.stream().sorted(Comparator.comparing(Surcharge::incomeAbove)).toList();
			for (int i = 1; i < sorted.size() && problem == null; i++) {
				if (sorted.get(i).incomeAbove().compareTo(sorted.get(i - 1).incomeAbove()) == 0) {
					problem = "Two surcharges start above %s.".formatted(fmt.money(sorted.get(i).incomeAbove()));
				}
				else if (sorted.get(i).rate().compareTo(sorted.get(i - 1).rate()) <= 0) {
					problem = "Each surcharge must be higher than the one for a lower income.";
				}
			}
		}
		if (problem != null) {
			problems.add(new Problem("surcharges", problem));
		}
	}

	/** Too many rows, a row with a missing value, or the first value a row breaks ("Row 2: enter zero or more."). */
	private static <T> String rowsProblem(List<T> rows, Predicate<T> incomplete, String whenIncomplete,
			Function<T, String> rowProblem) {
		if (rows.size() > MAX_ROWS) {
			return "Use at most %d rows.".formatted(MAX_ROWS);
		}
		if (rows.stream().anyMatch(incomplete)) {
			return whenIncomplete;
		}
		for (int i = 0; i < rows.size(); i++) {
			String problem = rowProblem.apply(rows.get(i));
			if (problem != null) {
				return "Row %d: %s".formatted(i + 1, Character.toLowerCase(problem.charAt(0)) + problem.substring(1));
			}
		}
		return null;
	}

	/**
	 * Upper limits of one set of slabs: exactly one open-ended top slab and no limit twice, so every income falls in
	 * exactly one slab.
	 */
	private String limitsProblem(List<BigDecimal> limits, String whose, String whenEmpty) {
		if (limits.isEmpty()) {
			return whenEmpty;
		}
		if (limits.stream().filter(Objects::isNull).count() != 1) {
			return "Leave “Up to” empty on exactly one slab%s: the top one, which has no upper limit.".formatted(whose);
		}
		Set<BigDecimal> seen = new HashSet<>();
		for (BigDecimal limit : limits) {
			if (limit != null && !seen.add(limit)) {
				return "Two slabs%s end at %s.".formatted(whose, fmt.money(limit));
			}
		}
		return null;
	}

	private static String moneyIfAny(BigDecimal value) {
		return (value == null) ? null : moneyProblem(value);
	}

	private static String firstOf(String... messages) {
		for (String message : messages) {
			if (message != null) {
				return message;
			}
		}
		return null;
	}

	private static String people(Gender gender) {
		return (gender == Gender.FEMALE) ? "women" : "men";
	}

	// ------------------------------------------------------------------ building and showing versions

	private static Rules build(RuleFamily family, NewVersion draft) {
		Map<String, BigDecimal> v = draft.values();
		return switch (family) {
			case WAGES -> new WageRules(v.get("excludedCapRate"), whole(v, "settlementDueWorkingDays"));
			case PF -> new PfRules(v.get("wageCeiling"), v.get("employeeRate"), v.get("employerRate"),
					v.get("epsRate"), whole(v, "epsAgeLimit"), v.get("edliRate"), v.get("adminRate"),
					v.get("adminMinimum"), v.get("edliAdminRate"));
			case ESI -> new EsiRules(v.get("wageCeiling"), v.get("disabilityWageCeiling"), v.get("employeeRate"),
					v.get("employerRate"), v.get("exemptDailyWage"), whole(v, "periodStartMonth"));
			case PT -> new PtRules(v.get("annualCap"), draft.ptSlabs());
			case NEW_TAX -> new TaxRules(v.get("standardDeduction"), v.get("rebateIncomeLimit"), v.get("rebateMax"),
					v.get("cessRate"), v.get("employerNpsRate"), draft.taxSlabs(), draft.surcharges());
			case OLD_TAX -> new TaxRules(v.get("standardDeduction"), v.get("rebateIncomeLimit"), v.get("rebateMax"),
					v.get("cessRate"), null, draft.taxSlabs(), draft.surcharges());
			case OLD_TAX_CAPS -> new DeductionCaps(v.get("investments"), v.get("healthInsurance"),
					v.get("homeLoanInterest"));
			case GRATUITY -> new GratuityRules(whole(v, "eligibilityYears"), whole(v, "fixedTermEligibilityYears"),
					whole(v, "daysPerYear"), whole(v, "monthDays"), whole(v, "partYearMonths"), v.get("maxAmount"));
		};
	}

	private static int whole(Map<String, BigDecimal> values, String key) {
		return values.get(key).intValueExact();
	}

	private static RuleVersion entity(RuleFamily family, IndianState state, Rules rules, NewVersion draft) {
		LocalDate from = draft.effectiveFrom();
		String note = draft.sourceNote();
		LocalDate verified = draft.verifiedOn();
		return switch (rules) {
			case WageRules wages -> new WageRuleVersion(wages, from, note, verified);
			case PfRules pf -> new PfRuleVersion(pf, from, note, verified);
			case EsiRules esi -> new EsiRuleVersion(esi, from, note, verified);
			case PtRules pt -> new PtRuleVersion(state, pt, from, note, verified);
			case TaxRules tax -> new TaxRuleVersion(family, tax, from, note, verified);
			case DeductionCaps caps -> new DeductionCapsVersion(caps, from, note, verified);
			case GratuityRules gratuity -> new GratuityRuleVersion(gratuity, from, note, verified);
		};
	}

	private FamilyView view(List<RuleVersion> group, LocalDate today) {
		RuleVersion current = group.stream().filter(version -> version.inForceOn(today)).findFirst().orElse(group.getFirst());
		RuleVersion next = group.stream()
			.filter(version -> version.getEffectiveFrom().isAfter(current.getEffectiveFrom()))
			.findFirst()
			.orElse(null);
		int earlier = (int) group.stream()
			.filter(version -> version.getEffectiveFrom().isBefore(current.getEffectiveFrom()))
			.count();
		RuleVersion latest = group.getLast();
		boolean withdrawable = group.size() > 1 && latest.getEffectiveFrom().isAfter(today);
		RuleFamily family = current.getFamily();
		List<String> nextChanges = (next == null) ? List.of()
				: List.copyOf(changes(family, current.rules(), next.rules()).before().keySet());
		return new FamilyView(VersionInfo.of(current), current.inForceOn(today), lines(family, current.rules()),
				tables(current.rules()), (next == null) ? null : VersionInfo.of(next), nextChanges, earlier,
				withdrawable ? VersionInfo.of(latest) : null);
	}

	/** The single values of {@code rules}, labelled and formatted, in page order. */
	public List<Line> lines(RuleFamily family, Rules rules) {
		Map<String, BigDecimal> values = rules.values();
		return family.fields()
			.stream()
			.map(field -> new Line(field.label(), text(field, values.get(field.key())), field.help()))
			.toList();
	}

	private String text(RuleField field, BigDecimal value) {
		if (value == null) {
			return null;
		}
		return switch (field.kind()) {
			case MONEY -> fmt.money(value);
			case RATE -> fmt.percent(value);
			case COUNT -> {
				int count = value.intValue();
				String unit = field.unit();
				yield count + " " + ((count == 1) ? unit.substring(0, unit.length() - 1) : unit);
			}
			case HALF_YEARS -> monthName(value.intValue()) + " and " + monthName(value.intValue() + EsiRules.PERIOD_MONTHS);
		};
	}

	/** {@code April}. */
	public static String monthName(int month) {
		return Month.of(month).getDisplayName(TextStyle.FULL, Locale.ENGLISH);
	}

	/** Professional tax and income-tax slabs as rows of text. */
	private List<Table> tables(Rules rules) {
		if (rules instanceof PtRules pt) {
			List<List<String>> rows = new ArrayList<>();
			for (Gender gender : Gender.PT_SLAB_GENDERS) {
				BigDecimal previous = null;
				for (PtSlab slab : pt.slabs(gender)) {
					rows.add(List.of(gender.label(), range(previous, slab.upTo()), fmt.money(slab.monthly()),
							fmt.money(slab.february())));
					previous = slab.upTo();
				}
			}
			return List.of(new Table("Slabs", "Slab", List.of("Gender", "Gross salary of the month", "Monthly PT",
					"In February"), 2, rows));
		}
		if (rules instanceof TaxRules tax) {
			List<List<String>> slabs = new ArrayList<>();
			BigDecimal previous = null;
			for (TaxSlab slab : tax.slabs()) {
				slabs.add(List.of(range(previous, slab.upTo()), fmt.percent(slab.rate())));
				previous = slab.upTo();
			}
			List<Table> tables = new ArrayList<>();
			tables.add(new Table("Slabs", "Slab", List.of("Total income", "Tax rate"), 1, slabs));
			if (!tax.surcharges().isEmpty()) {
				tables.add(new Table("Surcharges", "Surcharge", List.of("Total income", "Surcharge on tax"), 1,
						tax.surcharges()
							.stream()
							.map(row -> List.of("Above " + fmt.money(row.incomeAbove()), fmt.percent(row.rate())))
							.toList()));
			}
			return tables;
		}
		return List.of();
	}

	/** "Up to ₹7,500.00", "Above ₹7,500.00, up to ₹10,000.00", "Above ₹10,000.00". */
	private String range(BigDecimal above, BigDecimal upTo) {
		if (above == null) {
			return (upTo == null) ? "Any amount" : "Up to " + fmt.money(upTo);
		}
		return (upTo == null) ? "Above " + fmt.money(above)
				: "Above " + fmt.money(above) + ", up to " + fmt.money(upTo);
	}

	/** Each value and slab row that differs between two versions, as the page shows it. */
	private AuditChanges changes(RuleFamily family, Rules before, Rules after) {
		Map<String, String> was = describe(family, before);
		Map<String, String> now = describe(family, after);
		Set<String> labels = new LinkedHashSet<>(was.keySet());
		labels.addAll(now.keySet());
		AuditChanges changes = AuditChanges.create();
		labels.forEach(label -> changes.field(label, was.get(label), now.get(label)));
		return changes;
	}

	private Map<String, String> describe(RuleFamily family, Rules rules) {
		Map<String, String> described = new LinkedHashMap<>();
		lines(family, rules).forEach(line -> described.put(line.label(), line.value()));
		for (Table table : tables(rules)) {
			for (int i = 0; i < table.rows().size(); i++) {
				described.put(table.rowLabel() + " " + (i + 1), String.join(" · ", table.rows().get(i)));
			}
		}
		return described;
	}

	private static <R extends Rules> R rules(Map<RuleFamily, RuleVersion> versions, RuleFamily family, Class<R> type) {
		return type.cast(versions.get(family).rules());
	}

	private static String scope(RuleFamily family, IndianState state) {
		if (family.perState() != (state != null)) {
			throw new NotFoundException(family + " rules are not kept " + ((state == null) ? "across India" : "by state"));
		}
		return (state == null) ? RuleVersion.NATIONAL : state.name();
	}

	/**
	 * One family's versions in one scope, latest first.
	 * @throws NotFoundException when it has none (a state without professional tax rules)
	 */
	private List<RuleVersion> timeline(RuleFamily family, IndianState state) {
		String scope = scope(family, state);
		List<RuleVersion> timeline = versions.findByFamilyAndScopeOrderByEffectiveFromDesc(family, scope);
		if (timeline.isEmpty()) {
			throw new NotFoundException("No " + family + " rules for " + scope);
		}
		return timeline;
	}

}
