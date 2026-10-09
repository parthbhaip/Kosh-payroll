package com.kosh.company;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BinaryOperator;
import java.util.stream.IntStream;

import jakarta.persistence.EntityManager;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;

import com.kosh.audit.AuditAction;
import com.kosh.audit.AuditEvent;
import com.kosh.audit.AuditEventRepository;
import com.kosh.common.BusinessRuleException;
import com.kosh.common.EncryptedStringConverter;
import com.kosh.common.IndianState;
import com.kosh.config.AppProperties;
import com.kosh.statutory.PfPolicy;
import com.kosh.storage.FileStorageTest;
import com.kosh.web.Flash;

/**
 * The company details and pay settings pages (PLAN T11): defaults from migration V4, saving with inline errors, the PAN
 * encrypted at rest, every change audited with masked values, the logo, and that only admins change anything. Each test
 * runs in a rolled-back transaction; {@link CompanyLogoTest} covers the logo changes that must commit.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CompanySettingsTest {

	static final byte[] PNG = FileStorageTest.image("png", 1, 1);

	/** The 50% HRA cities migration V4 seeds (STATUTORY-RATES.md). */
	static final List<String> METROS = List.of("Delhi", "Mumbai", "Kolkata", "Chennai", "Bengaluru", "Hyderabad", "Pune",
			"Ahmedabad");

	private final MockMvc mvc;

	private final CompanyService service;

	private final CompanyRepository companies;

	private final AuditEventRepository events;

	private final JdbcTemplate jdbc;

	private final EntityManager entityManager;

	private final EncryptedStringConverter crypto;

	private final Path uploads;

	@Autowired
	CompanySettingsTest(MockMvc mvc, CompanyService service, CompanyRepository companies, AuditEventRepository events,
			JdbcTemplate jdbc, EntityManager entityManager, AppProperties app) {
		this.mvc = mvc;
		this.service = service;
		this.companies = companies;
		this.events = events;
		this.jdbc = jdbc;
		this.entityManager = entityManager;
		this.crypto = new EncryptedStringConverter(app);
		this.uploads = app.uploadDir();
	}

	// ------------------------------------------------------------------ company details

	@Test
	void anAdminSeesTheEmptyDetailsFormWithWhatIsMissing() throws Exception {
		String page = mvc.perform(get("/admin/company").with(admin()))
			.andExpect(status().isOk())
			.andExpect(view().name("admin/company/details"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(page).contains("<title>Company details | Kosh</title>")
			.contains("<a class=\"nav-link\" href=\"/admin/company\" title=\"Company\" aria-current=\"page\">")
			.contains("<a class=\"tab\" href=\"/admin/company\" aria-current=\"page\">Company details</a>")
			.contains("<a class=\"tab\" href=\"/admin/company/pay-settings\">Pay settings</a>")
			.contains("<option value=\"MH\">Maharashtra</option>")
			.contains("6 items are still missing:")
			.contains("Needed for the monthly PF ECR file.")
			.contains("No logo yet.")
			.contains("<form method=\"post\" enctype=\"multipart/form-data\" action=\"/admin/company/logo\" class=\"stack\">")
			.contains("href=\"/admin/audit?entity=Company\"")
			.doesNotContain("Remove logo");
	}

	@Test
	void savingDetailsTidiesTheValuesEncryptsThePanAndAuditsMasked() throws Exception {
		mvc.perform(post("/admin/company").with(admin()).with(csrf()).params(details()))
			.andExpect(redirectedUrl("/admin/company"))
			.andExpect(flash().attribute(Flash.ATTRIBUTE, Flash.success("Company details saved")
				.withText("Changed: Legal name, Brand name, Address line 1, City, State, PIN code, PAN, TAN, "
						+ "PF establishment code, ESI employer code, PT registration number, Authorised signatory, "
						+ "Signatory designation.")));

		assertThat(service.profile()).isEqualTo(new CompanyProfile("Panchganga Precision Tools Pvt. Ltd.",
				"Panchganga Precision Tools", "Plot C-14, MIDC Shiroli", null, "Kolhapur", IndianState.MH, "416122",
				"AABCP1234C", "KOLP12345E", "MHKOP0012345000", "31000123450001001", "27531234567P", "Anand Kulkarni",
				"Director"));
		entityManager.flush();
		String stored = jdbc.queryForObject("select pan from company where id = 1", String.class);
		assertThat(stored).isNotEqualTo("AABCP1234C").doesNotContain("1234").hasSize(52);
		assertThat(crypto.convertToEntityAttribute(stored)).isEqualTo("AABCP1234C");

		AuditEvent event = only(AuditAction.COMPANY_DETAILS_CHANGED);
		assertThat(event.getEntityType()).isEqualTo("Company");
		assertThat(event.getEntityId()).isEqualTo(1L);
		assertThat(event.getActorEmail()).isEqualTo("admin@company.kosh.test");
		assertThat(event.getSummary()).startsWith("Changed company details: Legal name, Brand name,");
		assertThat(event.getAfterJson()).contains("\"PAN\":\"AAXXXXX4C\"", "\"State\":\"Maharashtra\"",
				"\"TAN\":\"KOLP12345E\"").doesNotContain("AABCP1234C");
		assertThat(event.getBeforeJson()).contains("\"PAN\":null");

		String page = mvc.perform(get("/admin/company").with(admin())).andReturn().getResponse().getContentAsString();
		assertThat(page).contains("value=\"AABCP1234C\"")
			.contains("<option value=\"MH\" selected=\"selected\">Maharashtra</option>")
			.contains("Everything payslips and statutory files need is in place.");
	}

	@Test
	void savingTheSameDetailsAgainChangesAndRecordsNothing() throws Exception {
		mvc.perform(post("/admin/company").with(admin()).with(csrf()).params(details()));
		mvc.perform(post("/admin/company").with(admin()).with(csrf()).params(details()))
			.andExpect(redirectedUrl("/admin/company"))
			.andExpect(flash().attribute(Flash.ATTRIBUTE,
					Flash.info("Nothing to save").withText("The company details are as they were.")));
		assertThat(events.findAll().stream().filter(e -> e.getAction() == AuditAction.COMPANY_DETAILS_CHANGED))
			.hasSize(1);
	}

	@Test
	void aChangedPanIsAuditedAsMasksOnly() throws Exception {
		mvc.perform(post("/admin/company").with(admin()).with(csrf()).params(details()));
		LinkedMultiValueMap<String, String> changed = details();
		changed.set("pan", "AABCP9876D");
		mvc.perform(post("/admin/company").with(admin()).with(csrf()).params(changed))
			.andExpect(flash().attribute(Flash.ATTRIBUTE, Flash.success("Company details saved").withText("Changed: PAN.")));
		AuditEvent event = events.findAll()
			.stream()
			.filter(e -> e.getAction() == AuditAction.COMPANY_DETAILS_CHANGED)
			.reduce((first, second) -> second)
			.orElseThrow();
		assertThat(event.getSummary()).isEqualTo("Changed company details: PAN");
		assertThat(event.getBeforeJson()).isEqualTo("{\"PAN\":\"AAXXXXX4C\"}");
		assertThat(event.getAfterJson()).isEqualTo("{\"PAN\":\"AAXXXXX6D\"}");
	}

	@Test
	void badDetailsShowInlineErrorsKeepTheInputAndSaveNothing() throws Exception {
		String page = mvc.perform(post("/admin/company").with(admin())
			.with(csrf())
			.param("legalName", " ")
			.param("addressLine1", "Plot C-14, MIDC Shiroli")
			.param("city", "Kolhapur")
			.param("state", "XX")
			.param("pincode", "016122")
			.param("pan", "abcd1234f")
			.param("tan", "12345")
			.param("pfEstablishmentCode", "MH/KOP/12345")
			.param("esiEmployerCode", "3100012345")
			.param("ptRegistrationNumber", "27531234567")
			.param("signatoryName", "Anand Kulkarni"))
			.andExpect(status().isOk())
			.andExpect(view().name("admin/company/details"))
			.andExpect(model().attributeHasFieldErrors("form", "legalName", "state", "pincode", "pan", "tan",
					"pfEstablishmentCode", "esiEmployerCode", "ptRegistrationNumber", "signatoryDesignation"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(page).contains("Enter the company’s legal name.")
			.contains("Choose a value from the list.")
			.contains("Enter a 6-digit PIN code.")
			.contains("Enter a PAN like ABCDE1234F.")
			.contains("Enter a TAN like PNEP12345C.")
			.contains("Enter the 15-character code, like MHKOP0012345000.")
			.contains("Enter the 17-digit employer code.")
			.contains("Enter 11 digits followed by P, like 27531234567P.")
			.contains("Enter the signatory’s designation.")
			.contains("value=\"ABCD1234F\"")
			.contains("value=\"MH/KOP/12345\"")
			.contains("aria-invalid=\"true\"");
		assertThat(service.profile().legalName()).isNull();
		assertThat(events.findAll().stream().filter(e -> e.getAction() == AuditAction.COMPANY_DETAILS_CHANGED)).isEmpty();
	}

	// ------------------------------------------------------------------ logo

	@Test
	void anUploadedLogoIsServedToAdminsAndAnUploadThatRollsBackLeavesNoFile() throws Exception {
		mvc.perform(get("/admin/company/logo").with(admin())).andExpect(status().isNotFound());

		mvc.perform(multipart("/admin/company/logo").file(new MockMultipartFile("file", "logo.png", "image/png", PNG))
			.with(admin())
			.with(csrf()))
			.andExpect(redirectedUrl("/admin/company"))
			.andExpect(flash().attribute(Flash.ATTRIBUTE, Flash.success("Logo added")
				.withText("Payslips and salary letters generated from now on show it.")));
		String path = companies.findById(Company.ID).orElseThrow().getLogoPath();
		assertThat(uploads.resolve(path)).exists();

		mvc.perform(get("/admin/company/logo").with(admin()))
			.andExpect(status().isOk())
			.andExpect(content().contentType("image/png"))
			.andExpect(content().bytes(PNG));
		String page = mvc.perform(get("/admin/company").with(admin())).andReturn().getResponse().getContentAsString();
		assertThat(page).contains("<img src=\"/admin/company/logo\" alt=\"Current company logo\">")
			.contains("Replace logo")
			.contains("Remove logo");

		TestTransaction.flagForRollback();
		TestTransaction.end();
		assertThat(uploads.resolve(path)).doesNotExist();
	}

	// ------------------------------------------------------------------ pay settings

	@Test
	void paySettingsOpenWithTheDocumentedDefaultsAndThisMonthsDates() throws Exception {
		assertThat(service.paySettings()).isEqualTo(new PaySettings(ProrationMethod.CALENDAR_DAYS, 7, 3,
				PfPolicy.RESTRICT, false, true, 30, METROS, 15, 15, 7, 31));
		String page = mvc.perform(get("/admin/company/pay-settings").with(admin()))
			.andExpect(status().isOk())
			.andExpect(view().name("admin/company/pay-settings"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(page).contains("<title>Pay settings | Kosh</title>")
			.contains("<a class=\"tab\" href=\"/admin/company/pay-settings\" aria-current=\"page\">Pay settings</a>")
			.containsPattern("value=\"CALENDAR_DAYS\"[^>]*checked=\"checked\"")
			.containsPattern("value=\"RESTRICT\"[^>]*checked=\"checked\"")
			.doesNotContain("value=\"EXCLUDED\"")
			.contains("<option value=\"7\" selected=\"selected\">7th</option>")
			.contains("<option value=\"31\" selected=\"selected\">Last day of the month</option>")
			.contains("<option value=\"30\">30th</option>")
			.containsPattern("name=\"pfChargesInCtc\"[^>]*checked=\"checked\"")
			.doesNotContainPattern("name=\"gratuityInCtc\"[^>]*checked=\"checked\"")
			.contains(">Delhi, Mumbai, Kolkata, Chennai, Bengaluru, Hyderabad, Pune, Ahmedabad</textarea>")
			// The fixed test clock is 5 Oct 2026, so the payroll being worked on is September's.
			.contains("September 2026 payroll")
			.containsPattern("<dt>Attendance closes</dt>\\s*<dd>3 Oct 2026</dd>")
			.containsPattern("<dt>Salaries paid</dt>\\s*<dd>7 Oct 2026</dd>")
			.containsPattern("<dt>TDS deposit due</dt>\\s*<dd>7 Oct 2026</dd>")
			.containsPattern("<dt>PF due</dt>\\s*<dd>15 Oct 2026</dd>")
			.containsPattern("<dt>ESI due</dt>\\s*<dd>15 Oct 2026</dd>")
			.containsPattern("<dt>PT due</dt>\\s*<dd>31 Oct 2026</dd>")
			.doesNotContain("Pay day is after the 7th");
	}

	@Test
	void savingPaySettingsStoresThemAuditsTheChangesAndWarnsAboutALatePayDay() throws Exception {
		mvc.perform(post("/admin/company/pay-settings").with(admin()).with(csrf()).params(paySettings(Map.of())))
			.andExpect(redirectedUrl("/admin/company/pay-settings"))
			.andExpect(flash().attribute(Flash.ATTRIBUTE, Flash.success("Pay settings saved")
				.withText("Changed: Proration, Pay day, Attendance cut-off, Default PF policy, Gratuity provision in CTC, "
						+ "PF admin and EDLI in CTC, Leave encashment divisor, HRA metro cities, ESI due day. "
						+ "Payroll runs calculated from now on use them; finalized payslips stay as they are.")));

		assertThat(service.paySettings()).isEqualTo(new PaySettings(ProrationMethod.WORKING_DAYS, 10, 5,
				PfPolicy.ACTUAL, true, false, 26, List.of("Delhi", "Mumbai", "Kolkata", "Chennai", "Pune"), 15, 21, 7, 31));
		AuditEvent event = only(AuditAction.PAY_SETTINGS_CHANGED);
		assertThat(event.getSummary()).startsWith("Changed pay settings: Proration, Pay day,");
		assertThat(event.getBeforeJson()).contains("\"Proration\":\"Calendar days\"", "\"Pay day\":\"7th\"",
				"\"Gratuity provision in CTC\":\"No\"",
				"\"HRA metro cities\":\"Delhi, Mumbai, Kolkata, Chennai, Bengaluru, Hyderabad, Pune, Ahmedabad\"",
				"\"ESI due day\":\"15th\"");
		assertThat(event.getAfterJson()).contains("\"Proration\":\"Working days\"", "\"Pay day\":\"10th\"",
				"\"Default PF policy\":\"Actual PF wages\"", "\"Gratuity provision in CTC\":\"Yes\"",
				"\"PF admin and EDLI in CTC\":\"No\"", "\"ESI due day\":\"21st\"")
			.doesNotContain("PT due day");

		String page = mvc.perform(get("/admin/company/pay-settings").with(admin()))
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(page).contains("Pay day is after the 7th")
			.contains("September 2026 salaries would be paid on 10 Oct 2026.")
			.containsPattern("<dt>ESI due</dt>\\s*<dd>21 Oct 2026</dd>");
	}

	@Test
	void savingTheSamePaySettingsChangesNothing() throws Exception {
		mvc.perform(post("/admin/company/pay-settings").with(admin())
			.with(csrf())
			.params(paySettings(Map.of("prorationMethod", "CALENDAR_DAYS", "payDay", "7", "attendanceCutoffDay", "3",
					"defaultPfPolicy", "RESTRICT", "gratuityInCtc", "", "pfChargesInCtc", "true",
					"leaveEncashmentDivisor", "30", "metroCities",
					"Delhi,Mumbai,\nKolkata, Chennai,  Bengaluru, Hyderabad, Pune, Ahmedabad", "esiDueDay", "15"))))
			.andExpect(flash().attribute(Flash.ATTRIBUTE,
					Flash.info("Nothing to save").withText("The pay settings are as they were.")));
		assertThat(events.findAll().stream().filter(e -> e.getAction() == AuditAction.PAY_SETTINGS_CHANGED)).isEmpty();
	}

	@ParameterizedTest(name = "{0} = {1}")
	@CsvSource(delimiter = '|', textBlock = """
			attendanceCutoffDay    | 10      | Close attendance before pay day: choose a day before the 10th.
			attendanceCutoffDay    | 28      | Choose a cut-off from the 1st to the 27th.
			payDay                 | 29      | Choose a pay day from the 2nd to the 28th.
			payDay                 | 1       | Choose a pay day from the 2nd to the 28th.
			payDay                 | ten     | Enter a whole number.
			prorationMethod        | WEEKLY  | Choose a value from the list.
			prorationMethod        |         | Choose how to prorate.
			defaultPfPolicy        | EXCLUDED | Choose Restrict or Actual. Leaving PF is decided per employee, never for everyone.
			leaveEncashmentDivisor | 5       | Use a divisor from 20 to 31. Most companies use 30 or 26.
			leaveEncashmentDivisor | 30.5    | Enter a whole number.
			metroCities            | Delhi, 42 | “42” isn’t a city name. Use letters and spaces, for example Navi Mumbai.
			metroCities            | ,       | Enter at least one city.
			ptDueDay               | 0       | Choose a day of the month.
			tdsDueDay              |         | Choose the TDS due day.
			""")
	void badPaySettingsAreRefusedNextToTheField(String field, String value, String message) throws Exception {
		String page = mvc.perform(post("/admin/company/pay-settings").with(admin())
			.with(csrf())
			.params(paySettings(Map.of(field, (value == null) ? "" : value))))
			.andExpect(status().isOk())
			.andExpect(view().name("admin/company/pay-settings"))
			.andExpect(model().attributeHasFieldErrors("form", field))
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(page).contains(message);
		assertThat(service.paySettings().payDay()).isEqualTo(7);
		assertThat(events.findAll().stream().filter(e -> e.getAction() == AuditAction.PAY_SETTINGS_CHANGED)).isEmpty();
	}

	@Test
	void aCityAddedToALongMetroListIsSavedAndAudited() throws Exception {
		String nineteen = "Delhi, Mumbai, Kolkata, Chennai, Bengaluru, Hyderabad, Navi Mumbai, Ahmedabad, Surat, Lucknow, "
				+ "Kanpur, Nagpur, Indore, Aurangabad, Bhopal, Visakhapatnam, Pimpri-Chinchwad, Vadodara, Ghaziabad";
		assertThat(nineteen).hasSizeGreaterThan(190);
		mvc.perform(post("/admin/company/pay-settings").with(admin())
			.with(csrf())
			.params(paySettings(Map.of("metroCities", nineteen))))
			.andExpect(redirectedUrl("/admin/company/pay-settings"));

		mvc.perform(post("/admin/company/pay-settings").with(admin())
			.with(csrf())
			.params(paySettings(Map.of("metroCities", nineteen + ", Jaipur"))))
			.andExpect(flash().attribute(Flash.ATTRIBUTE, Flash.success("Pay settings saved")
				.withText("Changed: HRA metro cities. "
						+ "Payroll runs calculated from now on use them; finalized payslips stay as they are.")));
		assertThat(service.paySettings().metroCities()).hasSize(20).endsWith("Jaipur");
		AuditEvent last = events.findAll()
			.stream()
			.filter(e -> e.getAction() == AuditAction.PAY_SETTINGS_CHANGED)
			.reduce((first, second) -> second)
			.orElseThrow();
		assertThat(last.getSummary()).isEqualTo("Changed pay settings: HRA metro cities");
		assertThat(last.getBeforeJson()).isEqualTo("{\"HRA metro cities\":\"" + nineteen + "\"}");
		assertThat(last.getAfterJson()).isEqualTo("{\"HRA metro cities\":\"" + nineteen + ", Jaipur\"}");
	}

	@Test
	void aMetroListLongerThanTwoHundredCharactersIsRefusedOnTheField() throws Exception {
		String tooLong = "Delhi, Mumbai, Kolkata, Chennai, Bengaluru, Hyderabad, Navi Mumbai, Ahmedabad, Surat, Lucknow, "
				+ "Kanpur, Nagpur, Indore, Aurangabad, Bhopal, Visakhapatnam, Pimpri-Chinchwad, Vadodara, Ghaziabad, "
				+ "Thiruvananthapuram";
		assertThat(tooLong).hasSizeGreaterThan(PaySettings.MAX_CITIES_TEXT);
		String page = mvc.perform(post("/admin/company/pay-settings").with(admin())
			.with(csrf())
			.params(paySettings(Map.of("metroCities", tooLong))))
			.andExpect(status().isOk())
			.andExpect(model().attributeHasFieldErrors("form", "metroCities"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(page).contains("Keep the list within 200 characters.").contains("Thiruvananthapuram</textarea>");
		assertThat(service.paySettings().metroCities()).isEqualTo(METROS);
	}

	// ------------------------------------------------------------------ service rules for any caller

	@Test
	@WithMockUser(username = "admin@company.kosh.test", roles = "ADMIN")
	void theServiceChecksPaySettingRangesWhateverTheCaller() {
		assertRefused(() -> service.updatePaySettings(settings(1, 3, 30, 15)), "payDay");
		assertRefused(() -> service.updatePaySettings(settings(29, 3, 30, 15)), "payDay");
		assertRefused(() -> service.updatePaySettings(settings(7, 0, 30, 15)), "attendanceCutoffDay");
		assertRefused(() -> service.updatePaySettings(settings(7, 3, 0, 15)), "leaveEncashmentDivisor");
		assertRefused(() -> service.updatePaySettings(settings(7, 3, 32, 15)), "leaveEncashmentDivisor");
		assertRefused(() -> service.updatePaySettings(settings(7, 3, 30, 32)), "pfDueDay");
		assertThat(service.paySettings().leaveEncashmentDivisor()).isEqualTo(30);
		assertThat(events.findAll().stream().filter(e -> e.getAction() == AuditAction.PAY_SETTINGS_CHANGED)).isEmpty();
	}

	@Test
	@WithMockUser(username = "admin@company.kosh.test", roles = "ADMIN")
	void theServiceRefusesMissingChoicesAndBadCityListsWhateverTheCaller() {
		List<String> delhi = List.of("Delhi");
		assertRefused(() -> service.updatePaySettings(settings(null, PfPolicy.RESTRICT, delhi)), "prorationMethod");
		assertRefused(() -> service.updatePaySettings(settings(ProrationMethod.CALENDAR_DAYS, null, delhi)),
				"defaultPfPolicy");
		assertRefused(() -> service.updatePaySettings(settings(ProrationMethod.CALENDAR_DAYS, PfPolicy.RESTRICT, null)),
				"metroCities");
		// One entry holding two names would read back from the comma-joined column as two cities.
		assertThatThrownBy(() -> service
			.updatePaySettings(settings(ProrationMethod.CALENDAR_DAYS, PfPolicy.RESTRICT, List.of("Pune, Nashik"))))
			.isInstanceOfSatisfying(BusinessRuleException.class,
					rule -> assertThat(rule.getField()).isEqualTo("metroCities"))
			.hasMessage("“Pune, Nashik” isn’t a city name. Use letters and spaces, for example Navi Mumbai.");
		List<String> twentyOne = IntStream.rangeClosed(1, 21).mapToObj(n -> "City " + (char) ('a' + n)).toList();
		assertThatThrownBy(() -> service
			.updatePaySettings(settings(ProrationMethod.CALENDAR_DAYS, PfPolicy.RESTRICT, twentyOne)))
			.hasMessage("List at most 20 cities.");
		assertThat(service.paySettings().metroCities()).isEqualTo(METROS);
		assertThat(events.findAll().stream().filter(e -> e.getAction() == AuditAction.PAY_SETTINGS_CHANGED)).isEmpty();
	}

	@Test
	@WithMockUser(username = "admin@company.kosh.test", roles = "ADMIN")
	void theServiceChecksProfileFormatsAndTheSignatoryWhateverTheCaller() {
		CompanyProfile valid = new CompanyProfile("Panchganga Precision Tools Pvt. Ltd.", null, "Plot C-14, MIDC Shiroli",
				null, "Kolhapur", IndianState.MH, "416122", "AABCP1234C", "KOLP12345E", null, null, null, null, null);
		assertRefused(() -> service.updateProfile(profile(valid, "pincode", "016122")), "pincode");
		assertRefused(() -> service.updateProfile(profile(valid, "pan", "AABCP1234")), "pan");
		assertRefused(() -> service.updateProfile(profile(valid, "tan", "KOLP1234E")), "tan");
		assertRefused(() -> service.updateProfile(profile(valid, "pfEstablishmentCode", "MH/KOP/12345")),
				"pfEstablishmentCode");
		assertRefused(() -> service.updateProfile(profile(valid, "esiEmployerCode", "3100012345")), "esiEmployerCode");
		assertRefused(() -> service.updateProfile(profile(valid, "ptRegistrationNumber", "27531234567")),
				"ptRegistrationNumber");
		assertRefused(() -> service.updateProfile(profile(valid, "signatoryName", "Anand Kulkarni")),
				"signatoryDesignation");
		assertThat(service.profile().legalName()).isNull();
		assertThat(service.updateProfile(valid)).contains("Legal name", "PAN");
	}

	@ParameterizedTest(name = "{0} up to {1}")
	@CsvSource({ "legalName, 150", "brandName, 80", "addressLine1, 150", "addressLine2, 150", "city, 60",
			"signatoryName, 120", "signatoryDesignation, 80" })
	@WithMockUser(username = "admin@company.kosh.test", roles = "ADMIN")
	void theServiceKeepsTextWithinTheColumnSizesWhateverTheCaller(String field, int max) {
		CompanyProfile valid = new CompanyProfile("Panchganga Precision Tools Pvt. Ltd.", null, "Plot C-14, MIDC Shiroli",
				null, "Kolhapur", IndianState.MH, "416122", "AABCP1234C", "KOLP12345E", null, null, null,
				"Anand Kulkarni", "Director");
		assertThatThrownBy(() -> service.updateProfile(profile(valid, field, "a".repeat(max + 1))))
			.isInstanceOfSatisfying(BusinessRuleException.class, rule -> assertThat(rule.getField()).isEqualTo(field))
			.hasMessage("Use at most " + max + " characters.");
		assertThat(service.profile().legalName()).isNull();

		CompanyProfile atTheLimit = profile(valid, field, "a".repeat(max));
		service.updateProfile(atTheLimit);
		entityManager.flush(); // the column takes it
		assertThat(service.profile()).isEqualTo(atTheLimit);
	}

	// ------------------------------------------------------------------ who may

	@ParameterizedTest
	@ValueSource(strings = { "HR", "PAYROLL", "FINANCE", "MANAGER", "EMPLOYEE" })
	void nobodyButAnAdminOpensOrChangesAnything(String role) throws Exception {
		RequestPostProcessor other = user("someone@company.kosh.test").roles(role, "EMPLOYEE");
		for (String path : new String[] { "/admin/company", "/admin/company/pay-settings", "/admin/company/logo" }) {
			mvc.perform(get(path).with(other)).andExpect(status().isForbidden());
		}
		mvc.perform(post("/admin/company").with(other).with(csrf()).params(details()))
			.andExpect(status().isForbidden());
		mvc.perform(post("/admin/company/pay-settings").with(other).with(csrf()).params(paySettings(Map.of())))
			.andExpect(status().isForbidden());
		mvc.perform(multipart("/admin/company/logo").file(new MockMultipartFile("file", "logo.png", "image/png", PNG))
			.with(other)
			.with(csrf())).andExpect(status().isForbidden());
		mvc.perform(post("/admin/company/logo/remove").with(other).with(csrf())).andExpect(status().isForbidden());
		assertThat(service.profile().legalName()).isNull();
		assertThat(service.hasLogo()).isFalse();
	}

	@Test
	void anonymousVisitorsAreSentToSignIn() throws Exception {
		for (MockHttpServletRequestBuilder request : List.of(get("/admin/company"), get("/admin/company/pay-settings"),
				get("/admin/company/logo"), post("/admin/company").with(csrf()).params(details()))) {
			mvc.perform(request).andExpect(status().isFound()).andExpect(redirectedUrl("/login"));
		}
	}

	@Test
	@WithMockUser(username = "hr@company.kosh.test", roles = { "HR", "PAYROLL", "FINANCE", "EMPLOYEE" })
	void theServiceRefusesChangesFromAnyoneButAnAdminButLetsPayrollReadThem() {
		CompanyProfile profile = service.profile();
		PaySettings settings = service.paySettings();
		assertThatThrownBy(() -> service.updateProfile(profile)).isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> service.updatePaySettings(settings)).isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> service.replaceLogo(new MockMultipartFile("file", "logo.png", "image/png", PNG)))
			.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(service::removeLogo).isInstanceOf(AccessDeniedException.class);
		assertThat(settings.payDay()).isEqualTo(7);
	}

	// ------------------------------------------------------------------ helpers

	private static RequestPostProcessor admin() {
		return user("admin@company.kosh.test").roles("ADMIN");
	}

	/** A complete, valid details form, typed the way people type: lower case, extra spaces. */
	static LinkedMultiValueMap<String, String> details() {
		LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("legalName", "  Panchganga Precision Tools   Pvt. Ltd. ");
		form.add("brandName", "Panchganga Precision Tools");
		form.add("addressLine1", "Plot C-14, MIDC Shiroli");
		form.add("addressLine2", "  ");
		form.add("city", "Kolhapur");
		form.add("state", "MH");
		form.add("pincode", "416 122");
		form.add("pan", " aabcp 1234 c ");
		form.add("tan", "kolp12345e");
		form.add("pfEstablishmentCode", "mhkop0012345000");
		form.add("esiEmployerCode", "31000123450001001");
		form.add("ptRegistrationNumber", "27531234567p");
		form.add("signatoryName", "Anand Kulkarni");
		form.add("signatoryDesignation", "Director");
		return form;
	}

	/** A valid pay settings form that changes most defaults; {@code overrides} replaces single fields. */
	private static LinkedMultiValueMap<String, String> paySettings(Map<String, String> overrides) {
		Map<String, String> values = new LinkedHashMap<>();
		values.put("prorationMethod", "WORKING_DAYS");
		values.put("payDay", "10");
		values.put("attendanceCutoffDay", "5");
		values.put("defaultPfPolicy", "ACTUAL");
		values.put("gratuityInCtc", "true");
		values.put("pfChargesInCtc", "");
		values.put("leaveEncashmentDivisor", "26");
		values.put("metroCities", "Delhi, Mumbai, Kolkata, Chennai, Pune");
		values.put("pfDueDay", "15");
		values.put("esiDueDay", "21");
		values.put("tdsDueDay", "7");
		values.put("ptDueDay", "31");
		values.putAll(overrides);
		LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		values.forEach((name, value) -> {
			boolean checkbox = name.equals("gratuityInCtc") || name.equals("pfChargesInCtc");
			if (checkbox) {
				// What the browser sends for a th:field checkbox: the marker always, the value only when ticked.
				form.add("_" + name, "on");
			}
			if (!checkbox || !value.isEmpty()) {
				form.add(name, value);
			}
		});
		return form;
	}

	private static PaySettings settings(int payDay, int cutoffDay, int divisor, int pfDueDay) {
		return new PaySettings(ProrationMethod.CALENDAR_DAYS, payDay, cutoffDay, PfPolicy.RESTRICT, false, true, divisor,
				List.of("Delhi"), pfDueDay, 15, 7, 31);
	}

	/** {@code profile} with one text field replaced. */
	private static CompanyProfile profile(CompanyProfile p, String field, String value) {
		BinaryOperator<String> v = (name, current) -> name.equals(field) ? value : current;
		return new CompanyProfile(v.apply("legalName", p.legalName()), v.apply("brandName", p.brandName()),
				v.apply("addressLine1", p.addressLine1()), v.apply("addressLine2", p.addressLine2()),
				v.apply("city", p.city()), p.state(), v.apply("pincode", p.pincode()), v.apply("pan", p.pan()),
				v.apply("tan", p.tan()), v.apply("pfEstablishmentCode", p.pfEstablishmentCode()),
				v.apply("esiEmployerCode", p.esiEmployerCode()), v.apply("ptRegistrationNumber", p.ptRegistrationNumber()),
				v.apply("signatoryName", p.signatoryName()), v.apply("signatoryDesignation", p.signatoryDesignation()));
	}

	/** Valid pay settings except for the proration, PF policy and cities given. */
	private static PaySettings settings(ProrationMethod proration, PfPolicy policy, List<String> cities) {
		return new PaySettings(proration, 7, 3, policy, false, true, 30, cities, 15, 15, 7, 31);
	}

	private static void assertRefused(ThrowingCallable call, String field) {
		assertThatThrownBy(call).isInstanceOfSatisfying(BusinessRuleException.class,
				rule -> assertThat(rule.getField()).isEqualTo(field));
	}

	private AuditEvent only(AuditAction action) {
		List<AuditEvent> found = events.findAll().stream().filter(event -> event.getAction() == action).toList();
		assertThat(found).as("%s events", action).hasSize(1);
		return found.getFirst();
	}

}
