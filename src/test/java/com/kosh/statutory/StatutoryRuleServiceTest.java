package com.kosh.statutory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Month;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.kosh.audit.AuditAction;
import com.kosh.audit.AuditEvent;
import com.kosh.audit.AuditEventRepository;
import com.kosh.common.BusinessRuleException;
import com.kosh.common.Gender;
import com.kosh.common.IndianState;
import com.kosh.common.NotFoundException;
import com.kosh.statutory.StatutoryRuleService.Added;
import com.kosh.statutory.StatutoryRuleService.FamilyView;
import com.kosh.statutory.StatutoryRuleService.NewVersion;
import com.kosh.statutory.StatutoryRuleService.Problem;
import com.kosh.statutory.StatutoryRuleService.Withdrawn;

/**
 * Statutory rule versions (PLAN T14): the V7 seed holds exactly the values of docs/STATUTORY-RATES.md from 1 April
 * 2026, {@code forDate} returns one snapshot of the rules in force, and a new version starts after the latest one and
 * closes it, audited. The test clock is Monday 5 October 2026. Each test is rolled back.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@WithMockUser(username = "admin@rules.kosh.test", roles = "ADMIN")
class StatutoryRuleServiceTest {

	private static final LocalDate TAX_YEAR_START = LocalDate.of(2026, 4, 1);

	private static final LocalDate TODAY = LocalDate.of(2026, 10, 5);

	private final StatutoryRuleService service;

	private final AuditEventRepository events;

	private final JdbcTemplate jdbc;

	@Autowired
	StatutoryRuleServiceTest(StatutoryRuleService service, AuditEventRepository events, JdbcTemplate jdbc) {
		this.service = service;
		this.events = events;
		this.jdbc = jdbc;
	}

	// ------------------------------------------------------------------ the seed (STATUTORY-RATES.md)

	@Test
	void theSeedHoldsExactlyTheDocumentedRatesFromTheStartOfTaxYear2026() {
		StatutoryRules rules = service.forDate(TODAY);

		assertThat(rules.date()).isEqualTo(TODAY);
		assertThat(rules.wages()).isEqualTo(new WageRules(money("50"), 2));
		assertThat(rules.pf()).isEqualTo(new PfRules(money("15000"), money("12"), money("12"), money("8.33"), 58,
				money("0.5"), money("0.5"), money("500"), money("0")));
		assertThat(rules.esi()).isEqualTo(new EsiRules(money("21000"), money("25000"), money("0.75"), money("3.25"),
				money("176"), 4));
		assertThat(rules.pt(IndianState.MH)).contains(new PtRules(money("2500"), List.of(
				new PtSlab(Gender.MALE, money("7500"), money("0"), money("0")),
				new PtSlab(Gender.MALE, money("10000"), money("175"), money("175")),
				new PtSlab(Gender.MALE, null, money("200"), money("300")),
				new PtSlab(Gender.FEMALE, money("25000"), money("0"), money("0")),
				new PtSlab(Gender.FEMALE, null, money("200"), money("300")))));
		assertThat(rules.professionalTax()).containsOnlyKeys(IndianState.MH);
		assertThat(rules.newRegime()).isEqualTo(new TaxRules(money("75000"), money("1200000"), money("60000"),
				money("4"), money("14"),
				List.of(slab("400000", "0"), slab("800000", "5"), slab("1200000", "10"), slab("1600000", "15"),
						slab("2000000", "20"), slab("2400000", "25"), slab(null, "30")),
				List.of(surcharge("5000000", "10"), surcharge("10000000", "15"), surcharge("20000000", "25"))));
		assertThat(rules.oldRegime()).isEqualTo(new TaxRules(money("50000"), money("500000"), money("12500"),
				money("4"), null, List.of(slab("250000", "0"), slab("500000", "5"), slab("1000000", "20"), slab(null, "30")),
				List.of(surcharge("5000000", "10"), surcharge("10000000", "15"), surcharge("20000000", "25"),
						surcharge("50000000", "37"))));
		assertThat(rules.tax(TaxRegime.NEW)).isSameAs(rules.newRegime());
		assertThat(rules.tax(TaxRegime.OLD)).isSameAs(rules.oldRegime());
		assertThat(rules.oldRegimeCaps()).isEqualTo(new DeductionCaps(money("150000"), money("25000"), money("200000")));
		assertThat(rules.gratuity()).isEqualTo(new GratuityRules(5, 1, 15, 26, 6, money("2000000")));

		// Stored with the column scales, so values compare as the database holds them.
		assertThat(rules.pf().epsRate()).hasToString("8.3300");
		assertThat(rules.pf().wageCeiling()).hasToString("15000.00");

		assertThat(rules.versions()).hasSize(8)
			.allSatisfy(version -> {
				assertThat(version.effectiveFrom()).isEqualTo(TAX_YEAR_START);
				assertThat(version.effectiveTo()).isNull();
				assertThat(version.verifiedOn()).isEqualTo(LocalDate.of(2026, 10, 5));
				assertThat(version.sourceNote()).isNotBlank();
			})
			.extracting(VersionInfo::title)
			.containsExactly("Wages under the labour codes", "Provident Fund", "Employees’ State Insurance",
					"Professional tax, Maharashtra", "Income tax: new regime", "Income tax: old regime",
					"Old-regime deduction caps", "Gratuity");
		assertThat(rules.versions().get(1).sourceNote()).contains("https://www.indianpaycalculator.in/",
				"https://caclub.in/");
	}

	@Test
	void theSeedIsInForceFromOneAprilAndNothingIsInForceBefore() {
		assertThat(service.forDate(TAX_YEAR_START).pf().wageCeiling()).isEqualByComparingTo("15000");
		assertThat(service.forDate(LocalDate.of(2027, 3, 31)).newRegime().rebateMax()).isEqualByComparingTo("60000");

		assertThatThrownBy(() -> service.forDate(LocalDate.of(2026, 3, 31))).isInstanceOf(BusinessRuleException.class)
			.hasMessage("No statutory rules are in force on 31 Mar 2026 for Wages under the labour codes, Provident Fund, "
					+ "Employees’ State Insurance, Income tax: new regime, Income tax: old regime, Old-regime deduction "
					+ "caps, Gratuity. Add a version under Statutory rules first.");
	}

	@ParameterizedTest(name = "{0} earning {1} in {2} pays {3}")
	@CsvSource({ "MALE, 7500, SEPTEMBER, 0", "MALE, 7500.50, SEPTEMBER, 175", "MALE, 9000, SEPTEMBER, 175",
			"MALE, 10000, FEBRUARY, 175", "MALE, 25000, SEPTEMBER, 200", "MALE, 25000, FEBRUARY, 300",
			"FEMALE, 24000, SEPTEMBER, 0", "FEMALE, 25000, FEBRUARY, 0", "FEMALE, 30000, SEPTEMBER, 200",
			"FEMALE, 30000, FEBRUARY, 300" })
	void professionalTaxSlabsGiveTheW4Amounts(Gender gender, BigDecimal gross, Month month, BigDecimal pt) {
		PtRules maharashtra = service.forDate(TODAY).pt(IndianState.MH).orElseThrow();

		assertThat(maharashtra.slabFor(gender, gross).amountFor(month)).isEqualByComparingTo(pt);
	}

	@Test
	void aMaleEmployeeAboveTheTopLimitEveryMonthPaysTheAnnualMaximum() {
		PtRules maharashtra = service.forDate(TODAY).pt(IndianState.MH).orElseThrow();
		BigDecimal year = BigDecimal.ZERO;
		for (Month month : Month.values()) {
			year = year.add(maharashtra.slabFor(Gender.MALE, money("25000")).amountFor(month));
		}
		assertThat(year).isEqualByComparingTo(maharashtra.annualCap()).isEqualByComparingTo("2500");
		assertThat(service.forDate(TODAY).pt(IndianState.KA)).isEmpty();
	}

	@Test
	void esiContributionPeriodsStartInAprilAndOctober() {
		EsiRules esi = service.forDate(TODAY).esi();

		assertThat(esi.periodStart(LocalDate.of(2026, 4, 1))).isEqualTo(LocalDate.of(2026, 4, 1));
		assertThat(esi.periodStart(LocalDate.of(2026, 9, 30))).isEqualTo(LocalDate.of(2026, 4, 1));
		assertThat(esi.periodStart(LocalDate.of(2026, 10, 5))).isEqualTo(LocalDate.of(2026, 10, 1));
		assertThat(esi.periodStart(LocalDate.of(2027, 3, 31))).isEqualTo(LocalDate.of(2026, 10, 1));
		assertThat(esi.periodStart(LocalDate.of(2027, 1, 15))).isEqualTo(LocalDate.of(2026, 10, 1));
	}

	@Test
	void twoVersionsInForceOnOneDayFailLoudlyInsteadOfPickingOne() {
		// A migration that inserts a PF version without closing the seed's.
		jdbc.update("""
				insert into statutory_rules (family, scope, effective_from, source_note, verified_on, created_at,
				    updated_at, version)
				values ('PF', 'IN', '2026-05-01', 'Overlapping', '2026-10-05', now(), now(), 0)""");
		long id = jdbc.queryForObject("select id from statutory_rules where family = 'PF' and effective_from = '2026-05-01'",
				Long.class);
		jdbc.update("""
				insert into pf_rules (id, wage_ceiling, employee_rate, employer_rate, eps_rate, eps_age_limit, edli_rate,
				    admin_rate, admin_minimum, edli_admin_rate)
				values (?, 21000.00, 12.0000, 12.0000, 8.3300, 58, 0.5000, 0.5000, 500.00, 0.0000)""", id);

		assertThatThrownBy(() -> service.forDate(TODAY)).isInstanceOf(IllegalStateException.class)
			.hasMessageStartingWith("Two versions of Provident Fund are in force on 2026-10-05: id ")
			.hasMessageContaining("id " + id);
		assertThat(service.forDate(LocalDate.of(2026, 4, 30)).pf().wageCeiling()).isEqualByComparingTo("15000");
	}

	@Test
	void theSeedCannotHoldTwoVersionsStartingTheSameDay() {
		assertThatThrownBy(() -> jdbc.update("""
				insert into statutory_rules (family, scope, effective_from, source_note, verified_on, created_at,
				    updated_at, version)
				values ('PF', 'IN', '2026-04-01', 'Duplicate', '2026-10-05', now(), now(), 0)"""))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	// ------------------------------------------------------------------ adding versions

	@Test
	void aNewVersionClosesTheLatestOneTheDayBeforeAndAppliesFromItsDate() {
		Added added = service.add(RuleFamily.PF, null, change(RuleFamily.PF, null, LocalDate.of(2026, 11, 1),
				Map.of("wageCeiling", money("21000"), "edliRate", money("0.25"))));

		assertThat(added.version().effectiveFrom()).isEqualTo(LocalDate.of(2026, 11, 1));
		assertThat(added.version().effectiveTo()).isNull();
		assertThat(added.previous().effectiveFrom()).isEqualTo(TAX_YEAR_START);
		assertThat(added.previous().effectiveTo()).isEqualTo(LocalDate.of(2026, 10, 31));
		assertThat(added.changed()).containsExactly("Wage ceiling", "EDLI");

		assertThat(service.forDate(LocalDate.of(2026, 10, 31)).pf().wageCeiling()).isEqualByComparingTo("15000");
		StatutoryRules november = service.forDate(LocalDate.of(2026, 11, 1));
		assertThat(november.pf().wageCeiling()).isEqualByComparingTo("21000");
		assertThat(november.pf().edliRate()).isEqualByComparingTo("0.25");
		assertThat(november.pf().employeeRate()).isEqualByComparingTo("12");
		assertThat(november.versions()).extracting(VersionInfo::id).contains(added.version().id());
		assertThat(november.esi()).isEqualTo(service.forDate(TODAY).esi());
		assertThat(jdbc.queryForObject("select count(*) from statutory_rules where family = 'PF'", Integer.class))
			.isEqualTo(2);
		assertThat(jdbc.queryForObject("select wage_ceiling from pf_rules where id = ?", BigDecimal.class,
				added.version().id())).isEqualByComparingTo("21000");

		AuditEvent event = only(AuditAction.STATUTORY_VERSION_ADDED, added.version().id());
		assertThat(event.getActorEmail()).isEqualTo("admin@rules.kosh.test");
		assertThat(event.getSummary())
			.isEqualTo("Added Provident Fund from 1 Nov 2026; the version from 1 Apr 2026 ends on 31 Oct 2026");
		assertThat(event.getBeforeJson()).contains("Wage ceiling", "15,000.00", "EDLI", "0.5%")
			.doesNotContain("Employee share");
		assertThat(event.getAfterJson()).contains("Wage ceiling", "21,000.00", "0.25%");
	}

	@Test
	void slabChangesAreSavedSortedAndListedRowByRow() {
		IndianState mh = IndianState.MH;
		NewVersion draft = change(RuleFamily.PT, mh, LocalDate.of(2027, 4, 1), Map.of());
		List<PtSlab> slabs = List.of(new PtSlab(Gender.FEMALE, null, money("200"), money("300")),
				new PtSlab(Gender.MALE, money("10000"), money("175"), money("175")),
				new PtSlab(Gender.FEMALE, money("30000"), money("0"), money("0")),
				new PtSlab(Gender.MALE, null, money("200"), money("300")),
				new PtSlab(Gender.MALE, money("7500"), money("0"), money("0")));
		Added added = service.add(RuleFamily.PT, mh, new NewVersion(draft.effectiveFrom(), draft.values(), slabs,
				List.of(), List.of(), "Maharashtra Finance Act 2027 (example).", TODAY));

		// Row 4 now ends at 30,000 and row 5 starts above it.
		assertThat(added.changed()).containsExactly("Slab 4", "Slab 5");
		PtRules next = service.forDate(LocalDate.of(2027, 4, 1)).pt(mh).orElseThrow();
		assertThat(next.slabs()).extracting(PtSlab::gender)
			.containsExactly(Gender.MALE, Gender.MALE, Gender.MALE, Gender.FEMALE, Gender.FEMALE);
		assertThat(next.slabs(Gender.FEMALE)).extracting(PtSlab::upTo).containsExactly(money("30000.00"), null);
		assertThat(next.slabFor(Gender.FEMALE, money("28000")).monthly()).isEqualByComparingTo("0");
		assertThat(service.forDate(LocalDate.of(2027, 3, 31)).pt(mh).orElseThrow()
			.slabFor(Gender.FEMALE, money("28000"))
			.monthly()).isEqualByComparingTo("200");
		AuditEvent event = only(AuditAction.STATUTORY_VERSION_ADDED, added.version().id());
		assertThat(event.getBeforeJson()).contains("Slab 4", "25,000.00").doesNotContain("Slab 1");
		assertThat(event.getAfterJson()).contains("Slab 4", "Slab 5", "30,000.00");
	}

	@Test
	void anOverlappingOrEarlierStartIsRefused() {
		assertRefused(RuleFamily.PF, LocalDate.of(2026, 4, 1), "effectiveFrom",
				"Choose a date after 1 Apr 2026, when the latest version starts.");
		assertRefused(RuleFamily.PF, LocalDate.of(2026, 3, 1), "effectiveFrom",
				"Choose a date after 1 Apr 2026, when the latest version starts.");

		service.add(RuleFamily.PF, null,
				change(RuleFamily.PF, null, LocalDate.of(2027, 1, 1), Map.of("wageCeiling", money("21000"))));
		// The new latest version is from 1 January; nothing may start on or before it, even after 1 April 2026.
		assertRefused(RuleFamily.PF, LocalDate.of(2026, 11, 1), "effectiveFrom",
				"Choose a date after 1 Jan 2027, when the latest version starts.");
		assertRefused(RuleFamily.PF, LocalDate.of(2027, 1, 1), "effectiveFrom",
				"Choose a date after 1 Jan 2027, when the latest version starts.");
		assertThat(jdbc.queryForList("select effective_to from statutory_rules where family = 'PF' order by effective_from",
				LocalDate.class)).containsExactly(LocalDate.of(2026, 12, 31), null);
		// Other families keep their own timeline.
		Added esi = service.add(RuleFamily.ESI, null,
				change(RuleFamily.ESI, null, LocalDate.of(2026, 11, 1), Map.of("wageCeiling", money("25000"),
						"disabilityWageCeiling", money("30000"))));
		assertThat(esi.previous().effectiveTo()).isEqualTo(LocalDate.of(2026, 10, 31));
	}

	@Test
	void versionsStartOnTheFirstOfAMonthAndTaxVersionsOnOneApril() {
		assertRefused(RuleFamily.PF, LocalDate.of(2026, 11, 15), "effectiveFrom",
				"Choose the 1st of a month. Payroll applies one set of rules to a whole month.");
		assertRefused(RuleFamily.NEW_TAX, LocalDate.of(2026, 11, 1), "effectiveFrom",
				"Choose 1 April. These rules apply to a whole tax year, April to March.");
		assertRefused(RuleFamily.OLD_TAX_CAPS, LocalDate.of(2027, 1, 1), "effectiveFrom",
				"Choose 1 April. These rules apply to a whole tax year, April to March.");
		assertRefused(RuleFamily.GRATUITY, LocalDate.of(2100, 1, 1), "effectiveFrom", "Choose a date before 2100.");
		assertRefused(RuleFamily.WAGES, null, "effectiveFrom", "Enter the date the new values take effect.");

		Added tax = service.add(RuleFamily.NEW_TAX, null,
				change(RuleFamily.NEW_TAX, null, LocalDate.of(2027, 4, 1), Map.of("standardDeduction", money("100000"))));
		assertThat(tax.previous().effectiveTo()).isEqualTo(LocalDate.of(2027, 3, 31));
		assertThat(service.forDate(LocalDate.of(2027, 4, 1)).newRegime().standardDeduction())
			.isEqualByComparingTo("100000");
		assertThat(service.forDate(LocalDate.of(2027, 4, 1)).oldRegime().standardDeduction())
			.isEqualByComparingTo("50000");
	}

	@Test
	void theSameValuesAgainAreRefused() {
		NewVersion same = change(RuleFamily.GRATUITY, null, LocalDate.of(2026, 11, 1), Map.of());

		assertThatThrownBy(() -> service.add(RuleFamily.GRATUITY, null, same))
			.isInstanceOfSatisfying(BusinessRuleException.class, refused -> assertThat(refused.getField()).isNull())
			.hasMessage("These are the values of the version from 1 Apr 2026. Change at least one, or use “Mark as "
					+ "checked” on the rules page to record that you checked them.");
		assertThat(jdbc.queryForObject("select count(*) from statutory_rules where family = 'GRATUITY'", Integer.class))
			.isEqualTo(1);
	}

	@Test
	void everyValueIsCheckedWhateverTheCaller() {
		Map<String, BigDecimal> pf = new HashMap<>(Map.of("wageCeiling", money("-1"), "employeeRate", money("100.5"),
				"employerRate", money("12"), "epsRate", money("12.5"), "epsAgeLimit", money("58.5"), "edliRate",
				money("0.12345"), "adminRate", money("0.5"), "adminMinimum", money("500.001"), "edliAdminRate",
				money("0")));
		List<Problem> problems = service.problems(RuleFamily.PF, null,
				new NewVersion(LocalDate.of(2026, 11, 1), pf, null, null, null, " ", LocalDate.of(2026, 10, 6)));

		assertThat(problems).containsExactly(
				new Problem("values[wageCeiling]", "Enter zero or more."),
				new Problem("values[employeeRate]", "Enter a percentage from 0 to 100."),
				new Problem("values[epsAgeLimit]", "Enter a whole number from 18 to 100."),
				new Problem("values[edliRate]", "Use at most four decimals."),
				new Problem("values[adminMinimum]", "Use at most two decimals."),
				new Problem("values[epsRate]", "The pension share comes out of the employer share, so it can’t be larger."),
				new Problem("sourceNote", "Say where these values come from, such as the notification or a trusted summary."),
				new Problem("verifiedOn", "Enter today or an earlier date."));

		pf.remove("wageCeiling");
		pf.put("employeeRate", null);
		assertThat(service.problems(RuleFamily.PF, null,
				new NewVersion(LocalDate.of(2026, 11, 1), pf, null, null, null, "x".repeat(1001), null)))
			.contains(new Problem("values[wageCeiling]", "Enter an amount in rupees."),
					new Problem("values[employeeRate]", "Enter a percentage."),
					new Problem("sourceNote", "Use at most 1000 characters."),
					new Problem("verifiedOn", "Enter the date you checked these values against the source."));

		NewVersion esi = change(RuleFamily.ESI, null, LocalDate.of(2026, 11, 1),
				Map.of("disabilityWageCeiling", money("20000"), "wageCeiling", money("10000000000")));
		assertThat(service.problems(RuleFamily.ESI, null, esi)).containsExactly(
				new Problem("values[wageCeiling]", "Enter less than ₹1,000 crore."),
				new Problem("values[disabilityWageCeiling]", "Can’t be lower than the coverage ceiling."));
		assertThatThrownBy(() -> service.add(RuleFamily.ESI, null, esi)).isInstanceOfSatisfying(
				BusinessRuleException.class, refused -> assertThat(refused.getField()).isEqualTo("values[wageCeiling]"));
	}

	@Test
	void slabsMustCoverEveryIncomeExactlyOnce() {
		LocalDate april = LocalDate.of(2027, 4, 1);
		assertThat(ptProblem(List.of(new PtSlab(Gender.MALE, null, money("200"), money("300")))))
			.isEqualTo("Add the slabs for women.");
		assertThat(ptProblem(List.of(new PtSlab(Gender.MALE, money("7500"), money("0"), money("0")),
				new PtSlab(Gender.FEMALE, null, money("200"), money("300")))))
			.isEqualTo("Leave “Up to” empty on exactly one slab for men: the top one, which has no upper limit.");
		assertThat(ptProblem(List.of(new PtSlab(Gender.MALE, money("7500"), money("0"), money("0")),
				new PtSlab(Gender.MALE, money("7500"), money("175"), money("175")),
				new PtSlab(Gender.MALE, null, money("200"), money("300")),
				new PtSlab(Gender.FEMALE, null, money("200"), money("300")))))
			.isEqualTo("Two slabs for men end at ₹7,500.00.");
		assertThat(ptProblem(List.of(new PtSlab(null, null, money("200"), money("300")))))
			.isEqualTo("Choose the gender and enter both amounts of every slab.");
		assertThat(ptProblem(List.of(new PtSlab(Gender.MALE, null, money("200"), money("300")),
				new PtSlab(Gender.FEMALE, null, money("-200"), money("300")))))
			.isEqualTo("Row 2: enter zero or more.");

		NewVersion tax = change(RuleFamily.OLD_TAX, null, april, Map.of());
		assertThat(service.problems(RuleFamily.OLD_TAX, null, new NewVersion(april, tax.values(), null,
				List.of(slab("250000", "0"), slab(null, "5"), slab(null, "30")),
				List.of(surcharge("5000000", "10"), surcharge("10000000", "10")), "Finance Act", TODAY)))
			.containsExactly(
					new Problem("taxSlabs", "Leave “Up to” empty on exactly one slab: the top one, which has no upper limit."),
					new Problem("surcharges", "Each surcharge must be higher than the one for a lower income."));
		assertThat(service.problems(RuleFamily.OLD_TAX, null, new NewVersion(april, tax.values(), null, List.of(),
				List.of(surcharge("5000000", "10"), surcharge("5000000", "15")), "Finance Act", TODAY)))
			.containsExactly(new Problem("taxSlabs", "Add at least one slab."),
					new Problem("surcharges", "Two surcharges start above ₹50,00,000.00."));
		List<TaxSlab> thirteen = IntStream.range(0, 13)
			.mapToObj(i -> slab(i == 12 ? null : String.valueOf((i + 1) * 100000), "5"))
			.toList();
		assertThat(service.problems(RuleFamily.OLD_TAX, null,
				new NewVersion(april, tax.values(), null, thirteen, List.of(), "Finance Act", TODAY)))
			.containsExactly(new Problem("taxSlabs", "Use at most 12 rows."));
	}

	// ------------------------------------------------------------------ the page's data and marking as checked

	@Test
	void theOverviewShowsEveryFamilyInForceTodayWithItsNextVersion() {
		service.add(RuleFamily.PF, null,
				change(RuleFamily.PF, null, LocalDate.of(2026, 11, 1), Map.of("wageCeiling", money("21000"))));

		List<FamilyView> families = service.overview();

		assertThat(families).extracting(FamilyView::anchor)
			.containsExactly("wages", "pf", "esi", "pt-mh", "new-regime", "old-regime", "old-regime-caps", "gratuity");
		FamilyView pf = families.get(1);
		assertThat(pf.inForce()).isTrue();
		assertThat(pf.current().effectiveTo()).isEqualTo(LocalDate.of(2026, 10, 31));
		assertThat(pf.next().effectiveFrom()).isEqualTo(LocalDate.of(2026, 11, 1));
		assertThat(pf.nextChanges()).containsExactly("Wage ceiling");
		assertThat(pf.earlierVersions()).isZero();
		assertThat(pf.lines()).extracting(StatutoryRuleService.Line::value)
			.containsExactly("₹15,000.00", "12%", "12%", "8.33%", "58 years", "0.5%", "0.5%", "₹500.00", "0%");
		assertThat(families.get(2).lines()).extracting(StatutoryRuleService.Line::value)
			.containsExactly("₹21,000.00", "₹25,000.00", "0.75%", "3.25%", "₹176.00", "April and October");
		assertThat(families.get(0).lines()).extracting(StatutoryRuleService.Line::value)
			.containsExactly("50%", "2 working days");
		assertThat(families.get(7).lines()).extracting(StatutoryRuleService.Line::value)
			.containsExactly("5 years", "1 year", "15 days", "26 days", "6 months", "₹20,00,000.00");
		assertThat(families.get(3).tables().getFirst().rows()).containsExactly(
				List.of("Male", "Up to ₹7,500.00", "₹0.00", "₹0.00"),
				List.of("Male", "Above ₹7,500.00, up to ₹10,000.00", "₹175.00", "₹175.00"),
				List.of("Male", "Above ₹10,000.00", "₹200.00", "₹300.00"),
				List.of("Female", "Up to ₹25,000.00", "₹0.00", "₹0.00"),
				List.of("Female", "Above ₹25,000.00", "₹200.00", "₹300.00"));
		assertThat(families.get(5).tables()).extracting(StatutoryRuleService.Table::title)
			.containsExactly("Slabs", "Surcharges");
		assertThat(families.get(5).tables().get(1).rows().getLast()).containsExactly("Above ₹5,00,00,000.00", "37%");
	}

	@Test
	void markingAsCheckedRecordsTodayOnTheVersionInForce() {
		jdbc.update("update statutory_rules set verified_on = '2026-06-30' where family = 'ESI'");

		assertThat(service.markChecked(RuleFamily.ESI, null)).isTrue();
		assertThat(service.markChecked(RuleFamily.ESI, null)).isFalse();

		VersionInfo esi = service.forDate(TODAY).versions().get(2);
		assertThat(esi.verifiedOn()).isEqualTo(TODAY);
		AuditEvent event = only(AuditAction.STATUTORY_RULES_CHECKED, esi.id());
		assertThat(event.getSummary()).isEqualTo("Checked Employees’ State Insurance (from 1 Apr 2026) against its sources");
		assertThat(event.getBeforeJson()).contains("30 Jun 2026");
		assertThat(event.getAfterJson()).contains("5 Oct 2026");
	}

	// ------------------------------------------------------------------ withdrawing a scheduled version

	@Test
	void aScheduledVersionCanBeWithdrawnAndTheOneBeforeAppliesAgain() {
		IndianState mh = IndianState.MH;
		NewVersion draft = change(RuleFamily.PT, mh, LocalDate.of(2027, 4, 1), Map.of("annualCap", money("25000")));
		Added typo = service.add(RuleFamily.PT, mh, draft);
		long id = typo.version().id();
		assertThat(service.overview().get(3).withdrawable()).isEqualTo(typo.version());

		Withdrawn withdrawn = service.withdraw(RuleFamily.PT, mh, id);

		assertThat(withdrawn.version().effectiveFrom()).isEqualTo(LocalDate.of(2027, 4, 1));
		assertThat(withdrawn.previous().effectiveFrom()).isEqualTo(TAX_YEAR_START);
		assertThat(withdrawn.previous().effectiveTo()).isNull();
		assertThat(service.forDate(LocalDate.of(2027, 4, 1)).pt(mh).orElseThrow().annualCap())
			.isEqualByComparingTo("2500");
		for (String table : List.of("statutory_rules", "pt_rules")) {
			assertThat(jdbc.queryForObject("select count(*) from " + table + " where id = ?", Integer.class, id)).isZero();
		}
		assertThat(jdbc.queryForObject("select count(*) from pt_slabs where pt_rule_id = ?", Integer.class, id)).isZero();
		assertThat(jdbc.queryForList("select effective_to from statutory_rules where family = 'PT'", LocalDate.class))
			.containsExactly((LocalDate) null);
		assertThat(service.overview().get(3).withdrawable()).isNull();
		assertThat(service.overview().get(3).next()).isNull();

		AuditEvent event = only(AuditAction.STATUTORY_VERSION_WITHDRAWN, id);
		assertThat(event.getSummary()).isEqualTo("Withdrew Professional tax, Maharashtra from 1 Apr 2027 before it "
				+ "started; the version from 1 Apr 2026 has no end date again");
		assertThat(event.getBeforeJson()).contains("Annual maximum", "25,000.00");
		assertThat(event.getAfterJson()).contains("Annual maximum", "2,500.00");

		// The same date can now take the corrected values.
		service.add(RuleFamily.PT, mh, change(RuleFamily.PT, mh, LocalDate.of(2027, 4, 1), Map.of("annualCap",
				money("3000"))));
		assertThat(service.forDate(LocalDate.of(2027, 4, 1)).pt(mh).orElseThrow().annualCap())
			.isEqualByComparingTo("3000");
	}

	@Test
	void onlyTheLatestVersionCanBeWithdrawnAndOnlyBeforeItStarts() {
		long seed = service.draft(RuleFamily.PF, null).latest().id();
		assertWithdrawRefused(RuleFamily.PF, null, seed,
				"The version from 1 Apr 2026 has started, so it can’t be withdrawn. Add a new version to change its values.");

		// Back-dated to the 1st of this month: in force today, so it has started.
		Added october = service.add(RuleFamily.PF, null,
				change(RuleFamily.PF, null, LocalDate.of(2026, 10, 1), Map.of("wageCeiling", money("21000"))));
		assertWithdrawRefused(RuleFamily.PF, null, october.version().id(),
				"The version from 1 Oct 2026 has started, so it can’t be withdrawn. Add a new version to change its values.");
		assertThat(service.overview().get(1).withdrawable()).isNull();

		Added january = service.add(RuleFamily.PF, null,
				change(RuleFamily.PF, null, LocalDate.of(2027, 1, 1), Map.of("wageCeiling", money("25000"))));
		// The page showed the October version as the latest; it is not any more.
		assertWithdrawRefused(RuleFamily.PF, null, october.version().id(),
				"Someone changed these rules a moment ago. Check the rules page and try again.");
		assertThat(service.withdraw(RuleFamily.PF, null, january.version().id()).previous().effectiveFrom())
			.isEqualTo(LocalDate.of(2026, 10, 1));

		// A first version for a new state, inserted by a migration, has nothing to fall back to.
		jdbc.update("""
				insert into statutory_rules (family, scope, effective_from, source_note, verified_on, created_at,
				    updated_at, version)
				values ('PT', 'KA', '2027-04-01', 'Karnataka (example)', '2026-10-05', now(), now(), 0)""");
		long karnataka = jdbc.queryForObject("select id from statutory_rules where scope = 'KA'", Long.class);
		jdbc.update("insert into pt_rules (id, annual_cap) values (?, 2500.00)", karnataka);
		assertWithdrawRefused(RuleFamily.PT, IndianState.KA, karnataka,
				"It is the only version of these rules, so it can’t be withdrawn.");
		assertThatThrownBy(() -> service.withdraw(RuleFamily.PT, IndianState.GA, 1))
			.isInstanceOf(NotFoundException.class);
		assertThat(events.findAll()).filteredOn(event -> event.getAction() == AuditAction.STATUTORY_VERSION_WITHDRAWN)
			.extracting(AuditEvent::getEntityId)
			.containsExactly(january.version().id());
	}

	@Test
	void professionalTaxIsKeptByStateAndEverythingElseAcrossIndia() {
		assertThat(service.draft(RuleFamily.PT, IndianState.MH).latest().title())
			.isEqualTo("Professional tax, Maharashtra");
		assertThatThrownBy(() -> service.draft(RuleFamily.PT, null)).isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> service.draft(RuleFamily.PF, IndianState.MH)).isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> service.draft(RuleFamily.PT, IndianState.KA)).isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> service.markChecked(RuleFamily.PT, IndianState.KA)).isInstanceOf(NotFoundException.class);
	}

	@Test
	@WithMockUser(username = "payroll@rules.kosh.test", roles = { "HR", "PAYROLL", "FINANCE", "MANAGER", "EMPLOYEE" })
	void onlyAdminsSeeOrChangeTheRulesButPayrollCodeReadsThem() {
		NewVersion draft = new NewVersion(LocalDate.of(2026, 11, 1), Map.of(), null, null, null, "x", TODAY);

		assertThat(service.forDate(TODAY).pf().wageCeiling()).isEqualByComparingTo("15000");
		assertThatThrownBy(service::overview).isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> service.draft(RuleFamily.PF, null)).isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> service.problems(RuleFamily.PF, null, draft)).isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> service.add(RuleFamily.PF, null, draft)).isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> service.markChecked(RuleFamily.PF, null)).isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> service.withdraw(RuleFamily.PF, null, 1)).isInstanceOf(AccessDeniedException.class);
		assertThat(jdbc.queryForObject("select count(*) from statutory_rules", Integer.class)).isEqualTo(8);
	}

	// ------------------------------------------------------------------ helpers

	/** The latest version's values and slabs with {@code changes}, from {@code from}. */
	private NewVersion change(RuleFamily family, IndianState state, LocalDate from, Map<String, BigDecimal> changes) {
		Rules latest = service.draft(family, state).rules();
		Map<String, BigDecimal> values = new HashMap<>(latest.values());
		values.putAll(changes);
		List<PtSlab> pt = (latest instanceof PtRules rules) ? rules.slabs() : List.of();
		List<TaxSlab> slabs = (latest instanceof TaxRules rules) ? rules.slabs() : List.of();
		List<Surcharge> surcharges = (latest instanceof TaxRules rules) ? rules.surcharges() : List.of();
		return new NewVersion(from, values, pt, slabs, surcharges, "Notification of " + from + " (example).", TODAY);
	}

	private void assertRefused(RuleFamily family, LocalDate from, String field, String message) {
		NewVersion draft = change(family, null, LocalDate.of(2027, 4, 1), Map.of());
		NewVersion dated = new NewVersion(from, draft.values(), draft.ptSlabs(), draft.taxSlabs(), draft.surcharges(),
				draft.sourceNote(), draft.verifiedOn());
		assertThat(service.problems(family, null, dated)).containsExactly(new Problem(field, message));
		assertThatThrownBy(() -> service.add(family, null, dated)).isInstanceOfSatisfying(BusinessRuleException.class,
				refused -> assertThat(refused.getField()).isEqualTo(field))
			.hasMessage(message);
	}

	private void assertWithdrawRefused(RuleFamily family, IndianState state, long id, String message) {
		int before = jdbc.queryForObject("select count(*) from statutory_rules", Integer.class);
		assertThatThrownBy(() -> service.withdraw(family, state, id)).isInstanceOf(BusinessRuleException.class)
			.hasMessage(message);
		assertThat(jdbc.queryForObject("select count(*) from statutory_rules", Integer.class)).isEqualTo(before);
	}

	private String ptProblem(List<PtSlab> slabs) {
		NewVersion draft = change(RuleFamily.PT, IndianState.MH, LocalDate.of(2027, 4, 1), Map.of());
		return service.problems(RuleFamily.PT, IndianState.MH, new NewVersion(draft.effectiveFrom(), draft.values(),
				slabs, null, null, draft.sourceNote(), TODAY))
			.stream()
			.filter(problem -> "ptSlabs".equals(problem.field()))
			.map(Problem::message)
			.findFirst()
			.orElse(null);
	}

	private AuditEvent only(AuditAction action, long id) {
		List<AuditEvent> found = events.findAll()
			.stream()
			.filter(event -> event.getAction() == action && RuleVersion.AUDIT_TYPE.equals(event.getEntityType())
					&& Long.valueOf(id).equals(event.getEntityId()))
			.toList();
		assertThat(found).hasSize(1);
		return found.getFirst();
	}

	private static BigDecimal money(String value) {
		return new BigDecimal(value);
	}

	private static TaxSlab slab(String upTo, String rate) {
		return new TaxSlab((upTo == null) ? null : money(upTo), money(rate));
	}

	private static Surcharge surcharge(String above, String rate) {
		return new Surcharge(money(above), money(rate));
	}

}
