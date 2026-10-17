package com.kosh.statutory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import com.kosh.audit.AuditAction;
import com.kosh.web.Flash;

/**
 * {@code /admin/statutory} (PLAN T14): the rules in force with sources and last-verified dates, the new-version form
 * with its inline errors (overlapping dates included), "mark as checked" and who may open any of it. The test clock is
 * Monday 5 October 2026. Each test is rolled back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class StatutoryRulePageTest {

	private final MockMvc mvc;

	private final JdbcTemplate jdbc;

	@Autowired
	StatutoryRulePageTest(MockMvc mvc, JdbcTemplate jdbc) {
		this.mvc = mvc;
		this.jdbc = jdbc;
	}

	// ------------------------------------------------------------------ the rules page

	@Test
	void theRulesPageShowsEveryFamilyWithValuesSlabsSourcesAndLastVerifiedDates() throws Exception {
		String page = page(get("/admin/statutory"), "admin/statutory/overview");

		assertThat(page).contains("<title>Statutory rules | Kosh</title>")
			.contains("<a class=\"nav-link\" href=\"/admin/statutory\" title=\"Statutory rules\" aria-current=\"page\">")
			.contains("Rules change: check them before a payroll run")
			.containsSubsequence("id=\"wages\"", "id=\"pf\"", "id=\"esi\"", "id=\"pt-mh\"", "id=\"new-regime\"",
					"id=\"old-regime\"", "id=\"old-regime-caps\"", "id=\"gratuity\"")
			.contains("<a class=\"chip\" href=\"#pt-mh\">Professional tax, Maharashtra</a>")
			.contains("<h2 class=\"card-title\" id=\"pf-title\">Provident Fund</h2>")
			.containsPattern("<dt>Wage ceiling</dt>\\s*<dd>\\s*<span class=\"rule-value\">₹15,000.00</span>")
			.containsPattern("<dt>Pension \\(EPS\\)</dt>\\s*<dd>\\s*<span class=\"rule-value\">8.33%</span>")
			.containsPattern("<dt>No pension from age</dt>\\s*<dd>\\s*<span class=\"rule-value\">58 years</span>")
			.containsPattern("<dt>Contribution periods</dt>\\s*<dd>\\s*<span class=\"rule-value\">April and October</span>")
			.containsPattern("<dt>Full and final due within</dt>\\s*<dd>\\s*<span class=\"rule-value\">2 working days</span>")
			.containsPattern("<dt>Ceiling</dt>\\s*<dd>\\s*<span class=\"rule-value\">₹20,00,000.00</span>")
			.contains("<th scope=\"row\">Male</th>", "<td>Above ₹7,500.00, up to ₹10,000.00</td>",
					"<td class=\"num\">₹175.00</td>", "<td class=\"num\">₹300.00</td>")
			.contains("<th scope=\"row\">Above ₹24,00,000.00</th>", "<td class=\"num\">30%</td>")
			.contains("<th scope=\"row\">Above ₹5,00,00,000.00</th>", "<td class=\"num\">37%</td>")
			.contains("https://cleartax.in/s/professional-tax-maharashtra")
			.containsPattern("<dt>Last verified</dt>\\s*<dd>5 Oct 2026</dd>")
			.containsPattern("<dt>In force since</dt>\\s*<dd>1 Apr 2026</dd>")
			.containsPattern("<dt>Ends</dt>\\s*<dd>No end date</dd>")
			.contains("<span class=\"badge badge-success\">In force</span>")
			.contains("First version in Kosh")
			.contains("href=\"/admin/statutory/pf/new\"", "href=\"/admin/statutory/pt/new?state=MH\"",
					"href=\"/admin/statutory/new-regime/new\"")
			.contains("href=\"/admin/audit?entity=Statutory%20rule\"")
			.doesNotContain("New values from");
		// The seed was verified on the test clock's day, so no card offers "Mark as checked".
		assertThat(page.split("Checked today", -1)).hasSize(9);
		assertThat(page).doesNotContain("/checked\"");
	}

	@Test
	void aScheduledVersionIsAnnouncedOnTheCardInForce() throws Exception {
		mvc.perform(post("/admin/statutory/pf").with(admin()).with(csrf())
			.params(pfForm("2026-11-01", "values[wageCeiling]", "21,000"))).andExpect(redirectedUrl("/admin/statutory#pf"));

		String page = page(get("/admin/statutory"), "admin/statutory/overview");
		assertThat(page).contains("New values from 1 Nov 2026", "Changes: Wage ceiling.")
			.containsPattern("<dt>Ends</dt>\\s*<dd>31 Oct 2026</dd>")
			.containsPattern("<dt>Wage ceiling</dt>\\s*<dd>\\s*<span class=\"rule-value\">₹15,000.00</span>");
	}

	@Test
	void aScheduledVersionCanBeWithdrawnFromItsCard() throws Exception {
		mvc.perform(post("/admin/statutory/pf").with(admin()).with(csrf())
			.params(pfForm("2027-01-01", "values[wageCeiling]", "210000"))).andExpect(redirectedUrl("/admin/statutory#pf"));
		long id = jdbc.queryForObject("select id from statutory_rules where family = 'PF' and effective_from = '2027-01-01'",
				Long.class);

		String page = page(get("/admin/statutory"), "admin/statutory/overview");
		assertThat(page).contains("command=\"show-modal\" commandfor=\"pf-withdraw\">Withdraw the version from 1 Jan 2027"
				+ "<span class=\"visually-hidden\">: Provident Fund</span></button>")
			.contains("<dialog class=\"modal\" id=\"pf-withdraw\" aria-labelledby=\"pf-withdraw-title\" "
					+ "aria-describedby=\"pf-withdraw-text\">")
			.contains("<h2 id=\"pf-withdraw-title\">Withdraw the version from 1 Jan 2027?</h2>")
			.contains("The Provident Fund values from 1 Jan 2027 are deleted before they start")
			.containsPattern("(?s)<form method=\"post\"\\s+action=\"/admin/statutory/pf/withdraw\">.*?"
					+ "<input type=\"hidden\" name=\"version\" value=\"" + id + "\">\\s*<button class=\"btn btn-danger\"")
			.doesNotContain("id=\"esi-withdraw\"");

		mvc.perform(post("/admin/statutory/pf/withdraw").param("version", String.valueOf(id)).with(admin()).with(csrf()))
			.andExpect(redirectedUrl("/admin/statutory#pf"))
			.andExpect(flash().attribute(Flash.ATTRIBUTE, Flash.success("Version from 1 Jan 2027 withdrawn")
				.withText("Provident Fund. The version from 1 Apr 2026 has no end date again.")));
		assertNothingAdded();
		assertThat(page(get("/admin/statutory"), "admin/statutory/overview")).doesNotContain("New values from", "-withdraw");

		// A version in force is not withdrawn, whatever the request says.
		long seed = jdbc.queryForObject("select id from statutory_rules where family = 'PF'", Long.class);
		mvc.perform(post("/admin/statutory/pf/withdraw").param("version", String.valueOf(seed)).with(admin()).with(csrf()))
			.andExpect(redirectedUrl("/admin/statutory#pf"))
			.andExpect(flash().attribute(Flash.ATTRIBUTE, Flash.danger("The version from 1 Apr 2026 has started, so it "
					+ "can’t be withdrawn. Add a new version to change its values.")));
		assertNothingAdded();
	}

	// ------------------------------------------------------------------ the new-version form

	@Test
	void theFormStartsFromTheLatestVersion() throws Exception {
		String page = page(get("/admin/statutory/pf/new"), "admin/statutory/new-version");

		assertThat(page).contains("<title>New version: Provident Fund | Kosh</title>")
			.contains("<li><a href=\"/admin/statutory\">Statutory rules</a></li>")
			.contains("<form class=\"card\" method=\"post\" action=\"/admin/statutory/pf\"")
			.contains("Filled in from the version from 1 Apr 2026. Change what the new rules change.")
			.contains("<input class=\"input\" type=\"date\" id=\"effectiveFrom\" aria-describedby=\"effectiveFrom-help\" name=\"effectiveFrom\" value=\"\">")
			.contains("The 1st of a month after 1 Apr 2026. The latest version ends the day before.")
			.containsPattern("<label class=\"label\" for=\"value-wageCeiling\">Wage ceiling</label>")
			.containsPattern("id=\"value-wageCeiling\" name=\"values\\[wageCeiling\\]\"\\s+value=\"15000\"")
			.containsPattern("id=\"value-epsRate\" name=\"values\\[epsRate\\]\"\\s+value=\"8.33\"")
			.containsPattern("<span class=\"input-addon\" id=\"value-epsAgeLimit-unit\">years</span>")
			.containsPattern("<span class=\"input-addon\" id=\"value-wageCeiling-unit\" title=\"Rupees\">₹</span>")
			.contains("Wage ceiling of 15,000 rupees a month")
			.containsPattern("id=\"verifiedOn\" name=\"verifiedOn\" value=\"2026-10-05\"")
			.contains("Version it follows", "No end date yet")
			.contains("href=\"/admin/statutory#pf\"")
			.doesNotContain("ptSlabs[", "taxSlabs[");

		String esi = page(get("/admin/statutory/esi/new"), "admin/statutory/new-version");
		assertThat(esi).containsPattern("<option value=\"4\" selected=\"selected\">April and October</option>")
			.contains("<option value=\"1\">January and July</option>");
	}

	@Test
	void slabFormsListTheRowsWithTwoEmptyOnesForMore() throws Exception {
		String pt = page(get("/admin/statutory/pt/new").param("state", "MH"), "admin/statutory/new-version");
		assertThat(pt).contains("<title>New version: Professional tax, Maharashtra | Kosh</title>")
			.contains("action=\"/admin/statutory/pt?state=MH\"")
			.containsPattern("name=\"ptSlabs\\[4\\].upTo\"\\s+value=\"\"")
			.containsPattern("name=\"ptSlabs\\[6\\].gender\"")
			.doesNotContain("ptSlabs[7]")
			.containsPattern("name=\"ptSlabs\\[1\\].monthly\"\\s+value=\"175\"")
			.contains("<option value=\"FEMALE\" selected=\"selected\">Female</option>")
			.contains("The 1st of a month after 1 Apr 2026. The latest version ends the day before.");

		String tax = page(get("/admin/statutory/new-regime/new"), "admin/statutory/new-version");
		assertThat(tax).contains("1 April of a tax year after 1 Apr 2026. The latest version ends the day before.")
			.containsPattern("name=\"taxSlabs\\[0\\].amount\"\\s+value=\"400000\"")
			.containsPattern("name=\"taxSlabs\\[6\\].amount\"\\s+value=\"\"")
			.containsPattern("name=\"taxSlabs\\[6\\].rate\"\\s+value=\"30\"")
			.containsPattern("name=\"taxSlabs\\[8\\].rate\"")
			.containsPattern("name=\"surcharges\\[2\\].amount\"\\s+value=\"20000000\"")
			.containsPattern("name=\"surcharges\\[4\\].rate\"")
			.contains("<span class=\"input-addon\" id=\"value-cessRate-unit\">%</span>");
	}

	@Test
	void addingAVersionClosesTheOneBeforeAndSaysWhatChanged() throws Exception {
		mvc.perform(post("/admin/statutory/pf").with(admin()).with(csrf())
			.params(pfForm("2026-11-01", "values[wageCeiling]", "₹21,000", "values[edliRate]", "0.25 %")))
			.andExpect(redirectedUrl("/admin/statutory#pf"))
			.andExpect(flash().attribute(Flash.ATTRIBUTE, Flash.success("New version from 1 Nov 2026 added")
				.withText("Provident Fund. Changed: Wage ceiling, EDLI. The version from 1 Apr 2026 ends on 31 Oct 2026.")));

		assertThat(jdbc.queryForList("""
				select concat(s.effective_from, ' ', coalesce(cast(s.effective_to as varchar(10)), 'open'), ' ',
				    p.wage_ceiling, ' ', p.edli_rate)
				from statutory_rules s join pf_rules p on p.id = s.id order by s.effective_from""", String.class))
			.containsExactly("2026-04-01 2026-10-31 15000.00 0.5000", "2026-11-01 open 21000.00 0.2500");
		assertThat(jdbc.queryForObject("select count(*) from audit_events where action = ? and entity_type = ?",
				Integer.class, AuditAction.STATUTORY_VERSION_ADDED.name(), RuleVersion.AUDIT_TYPE)).isEqualTo(1);
	}

	@Test
	void anOverlappingDateIsShownOnTheFormAndNothingIsSaved() throws Exception {
		String page = page(post("/admin/statutory/pf").with(csrf())
			.params(pfForm("2026-04-01", "values[wageCeiling]", "21000")), "admin/statutory/new-version");

		assertThat(page).containsPattern("id=\"effectiveFrom\" aria-describedby=\"effectiveFrom-help effectiveFrom-error\" aria-invalid=\"true\" name=\"effectiveFrom\" value=\"2026-04-01\"")
			.contains("Choose a date after 1 Apr 2026, when the latest version starts.")
			.containsPattern("id=\"value-wageCeiling\" name=\"values\\[wageCeiling\\]\"\\s+value=\"21000\"");
		assertNothingAdded();

		String midMonth = page(post("/admin/statutory/pf").with(csrf()).params(pfForm("2026-11-15")),
				"admin/statutory/new-version");
		assertThat(midMonth).contains("Choose the 1st of a month. Payroll applies one set of rules to a whole month.");
		assertNothingAdded();
	}

	@Test
	void wrongEntriesAreShownBackAsTypedWithTheirMessages() throws Exception {
		String page = page(post("/admin/statutory/pf").with(csrf())
			.params(pfForm("2026-11-01", "values[wageCeiling]", "fifteen thousand", "values[employeeRate]", "1e2",
					"values[epsRate]", "13", "values[epsAgeLimit]", "", "sourceNote", " ", "verifiedOn", "2026-10-06")),
				"admin/statutory/new-version");

		assertThat(page).containsPattern("value=\"fifteen thousand\"[^>]*aria-invalid=\"true\"")
			.contains("Enter an amount in rupees, like 15000.")
			.contains("Enter a percentage, like 8.33.")
			.contains("Enter a whole number from 18 to 100.")
			.contains("The pension share comes out of the employer share, so it can’t be larger.")
			.contains("Say where these values come from, such as the notification or a trusted summary.")
			.contains("Enter today or an earlier date.")
			.doesNotContain("Enter a whole number.");
		assertNothingAdded();

		String sameValues = page(post("/admin/statutory/pf").with(csrf()).params(pfForm("2026-11-01")),
				"admin/statutory/new-version");
		assertThat(sameValues).contains("These are the values of the version from 1 Apr 2026.");
		assertNothingAdded();

		String badDate = page(post("/admin/statutory/pf").with(csrf()).params(pfForm("not-a-date")),
				"admin/statutory/new-version");
		assertThat(badDate).contains("Enter a real date, like 26 January 2027.")
			.doesNotContain("Enter the date the new values take effect.");
	}

	@Test
	void slabRowsAreCheckedRowByRowAndEmptyRowsAreIgnored() throws Exception {
		MockHttpServletRequestBuilder request = post("/admin/statutory/pt").param("state", "MH")
			.with(csrf())
			.param("effectiveFrom", "2027-04-01")
			.param("values[annualCap]", "2500")
			.param("sourceNote", "Maharashtra Finance Act 2027 (example).")
			.param("verifiedOn", "2026-10-05");
		ptRow(request, 0, "MALE", "7500", "0", "0");
		ptRow(request, 1, "", "", "", "");
		ptRow(request, 2, "MALE", "", "200", "300");
		ptRow(request, 3, "FEMALE", "abc", "0", "");
		ptRow(request, 4, "FEMALE", "", "200", "300");
		String page = page(request, "admin/statutory/new-version");

		// The empty second row is dropped, so the unreadable one is row 3 on the page that comes back.
		assertThat(page).contains("Row 3: enter the salary limit in rupees, or leave it empty for the top slab.")
			.contains("Row 3: enter the February PT.")
			.containsPattern("name=\"ptSlabs\\[2\\].upTo\"\\s+value=\"abc\"\\s+aria-invalid=\"true\" aria-describedby=\"pt-errors\"")
			.contains("<ul class=\"slab-errors\" id=\"pt-errors\">")
			.containsPattern("name=\"ptSlabs\\[1\\].monthly\"\\s+value=\"200\"");
		assertNothingAdded();

		MockHttpServletRequestBuilder fixed = post("/admin/statutory/pt").param("state", "MH")
			.with(admin())
			.with(csrf())
			.param("effectiveFrom", "2027-04-01")
			.param("values[annualCap]", "2,500")
			.param("sourceNote", "Maharashtra Finance Act 2027 (example).")
			.param("verifiedOn", "2026-10-05");
		ptRow(fixed, 0, "FEMALE", "", "200", "300");
		ptRow(fixed, 1, "MALE", "7500", "0", "0");
		ptRow(fixed, 2, "", "", "", "");
		ptRow(fixed, 3, "MALE", "10000", "175", "175");
		ptRow(fixed, 4, "MALE", "", "200", "300");
		ptRow(fixed, 5, "FEMALE", "30000", "0", "0");
		mvc.perform(fixed)
			.andExpect(redirectedUrl("/admin/statutory#pt-mh"))
			.andExpect(flash().attribute(Flash.ATTRIBUTE, Flash.success("New version from 1 Apr 2027 added")
				.withText("Professional tax, Maharashtra. Changed: Slab 4, Slab 5. The version from 1 Apr 2026 ends on "
						+ "31 Mar 2027.")));
		assertThat(jdbc.queryForList("""
				select concat(line_no, ' ', gender, ' ', coalesce(cast(salary_up_to as varchar(20)), 'top'))
				from pt_slabs where pt_rule_id = (select max(id) from statutory_rules where family = 'PT')
				order by line_no""", String.class))
			.containsExactly("0 MALE 7500.00", "1 MALE 10000.00", "2 MALE top", "3 FEMALE 30000.00", "4 FEMALE top");
	}

	@Test
	void taxVersionsStartOnOneApril() throws Exception {
		MockHttpServletRequestBuilder request = post("/admin/statutory/old-regime-caps").with(csrf())
			.param("effectiveFrom", "2027-01-01")
			.param("values[investments]", "200000")
			.param("values[healthInsurance]", "25000")
			.param("values[homeLoanInterest]", "200000")
			.param("sourceNote", "Finance Act 2027 (example).")
			.param("verifiedOn", "2026-10-05");
		assertThat(page(request, "admin/statutory/new-version"))
			.contains("Choose 1 April. These rules apply to a whole tax year, April to March.");
		assertNothingAdded();
	}

	@Test
	void markingAsCheckedRecordsTodayAndSaysSo() throws Exception {
		jdbc.update("update statutory_rules set verified_on = '2026-06-30' where family = 'GRATUITY'");

		String page = page(get("/admin/statutory"), "admin/statutory/overview");
		assertThat(page).contains("<form method=\"post\" action=\"/admin/statutory/gratuity/checked\">")
			.contains("Mark as checked<span class=\"visually-hidden\">: Gratuity</span>")
			.containsPattern("<dt>Last verified</dt>\\s*<dd>30 Jun 2026</dd>");

		mvc.perform(post("/admin/statutory/gratuity/checked").with(admin()).with(csrf()))
			.andExpect(redirectedUrl("/admin/statutory#gratuity"))
			.andExpect(flash().attribute(Flash.ATTRIBUTE,
					Flash.success("Gratuity marked as checked").withText("Last verified 5 Oct 2026.")));
		mvc.perform(post("/admin/statutory/pt/checked").param("state", "MH").with(admin()).with(csrf()))
			.andExpect(redirectedUrl("/admin/statutory#pt-mh"))
			.andExpect(flash().attribute(Flash.ATTRIBUTE, Flash.info("Already checked today")
				.withText("Professional tax, Maharashtra was marked as checked earlier today.")));
		assertThat(jdbc.queryForObject("select verified_on from statutory_rules where family = 'GRATUITY'",
				LocalDate.class)).isEqualTo(LocalDate.of(2026, 10, 5));
	}

	@Test
	void unknownFamiliesAndStatesAreNotFound() throws Exception {
		for (String path : List.of("/admin/statutory/bonus/new", "/admin/statutory/pt/new",
				"/admin/statutory/pt/new?state=KA", "/admin/statutory/pt/new?state=XX", "/admin/statutory/pf/new?state=MH")) {
			mvc.perform(get(path).with(admin())).andExpect(status().isNotFound());
		}
		mvc.perform(post("/admin/statutory/bonus/checked").with(admin()).with(csrf())).andExpect(status().isNotFound());
		mvc.perform(post("/admin/statutory/pt/checked").param("state", "KA").with(admin()).with(csrf()))
			.andExpect(status().isNotFound());
		mvc.perform(post("/admin/statutory/pt/withdraw").param("state", "KA").param("version", "1").with(admin())
			.with(csrf())).andExpect(status().isNotFound());
		mvc.perform(post("/admin/statutory/pt").with(admin()).with(csrf()).params(pfForm("2026-11-01")))
			.andExpect(status().isNotFound());
	}

	// ------------------------------------------------------------------ who gets in

	@ParameterizedTest
	@ValueSource(strings = { "HR", "PAYROLL", "FINANCE", "MANAGER", "EMPLOYEE" })
	void onlyAdminsGetIn(String role) throws Exception {
		RequestPostProcessor other = user("other@rules.kosh.test").roles(role, "EMPLOYEE");
		for (String path : List.of("/admin/statutory", "/admin/statutory/pf/new", "/admin/statutory/pt/new?state=MH")) {
			mvc.perform(get(path).with(other)).andExpect(status().isForbidden());
		}
		mvc.perform(post("/admin/statutory/pf").with(other).with(csrf()).params(newCeiling()))
			.andExpect(status().isForbidden());
		mvc.perform(post("/admin/statutory/pf/checked").with(other).with(csrf())).andExpect(status().isForbidden());
		mvc.perform(post("/admin/statutory/pf/withdraw").param("version", "1").with(other).with(csrf()))
			.andExpect(status().isForbidden());
		assertNothingAdded();
	}

	@Test
	void anonymousVisitorsAreSentToSignIn() throws Exception {
		mvc.perform(get("/admin/statutory").with(anonymous())).andExpect(redirectedUrl("/login"));
		mvc.perform(post("/admin/statutory/pf").with(anonymous()).with(csrf()).params(newCeiling()))
			.andExpect(redirectedUrl("/login"));
		// Without the CSRF token nothing is saved, even for an admin.
		mvc.perform(post("/admin/statutory/pf").with(admin()).params(newCeiling()))
			.andExpect(status().is3xxRedirection());
		assertNothingAdded();
	}

	/** A valid PF version from 1 November 2026 with a new wage ceiling. */
	private static MultiValueMap<String, String> newCeiling() {
		return pfForm("2026-11-01", "values[wageCeiling]", "21000");
	}

	// ------------------------------------------------------------------ helpers

	private static RequestPostProcessor admin() {
		return user("admin@rules.kosh.test").roles("ADMIN");
	}

	/** The PF form as the seed fills it in, from {@code from}, with {@code changes} as name-value pairs. */
	private static MultiValueMap<String, String> pfForm(String from, String... changes) {
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("effectiveFrom", from);
		form.add("values[wageCeiling]", "15000");
		form.add("values[employeeRate]", "12");
		form.add("values[employerRate]", "12");
		form.add("values[epsRate]", "8.33");
		form.add("values[epsAgeLimit]", "58");
		form.add("values[edliRate]", "0.5");
		form.add("values[adminRate]", "0.5");
		form.add("values[adminMinimum]", "500");
		form.add("values[edliAdminRate]", "0");
		form.add("sourceNote", "EPFO notification (example).");
		form.add("verifiedOn", "2026-10-05");
		for (int i = 0; i < changes.length; i += 2) {
			form.set(changes[i], changes[i + 1]);
		}
		return form;
	}

	private static void ptRow(MockHttpServletRequestBuilder request, int row, String gender, String upTo,
			String monthly, String february) {
		String at = "ptSlabs[" + row + "].";
		request.param(at + "gender", gender)
			.param(at + "upTo", upTo)
			.param(at + "monthly", monthly)
			.param(at + "february", february);
	}

	private void assertNothingAdded() {
		assertThat(jdbc.queryForObject("select count(*) from statutory_rules", Integer.class)).isEqualTo(8);
		assertThat(jdbc.queryForObject("select count(*) from statutory_rules where effective_to is not null",
				Integer.class)).isZero();
	}

	/** Renders a page as the admin; a request built with {@code .with(admin())} already keeps it. */
	private String page(RequestBuilder request, String view) throws Exception {
		RequestBuilder signedIn = (request instanceof MockHttpServletRequestBuilder builder) ? builder.with(admin())
				: request;
		return mvc.perform(signedIn)
			.andExpect(status().isOk())
			.andExpect(view().name(view))
			.andReturn()
			.getResponse()
			.getContentAsString();
	}

}
