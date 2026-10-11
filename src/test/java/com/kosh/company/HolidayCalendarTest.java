package com.kosh.company;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import com.kosh.audit.AuditAction;
import com.kosh.audit.AuditEvent;
import com.kosh.audit.AuditEventRepository;
import com.kosh.common.BusinessRuleException;
import com.kosh.common.NotFoundException;
import com.kosh.company.HolidayService.Details;
import com.kosh.org.OrgKind;
import com.kosh.org.OrgService;
import com.kosh.web.Flash;

/**
 * The holiday calendar and work week (PLAN T13): the year page, adding, editing, confirming and deleting holidays,
 * copying last year's list, the work week grid, the working-day answers every other feature uses, the audit trail and
 * that only HR and ADMIN keep the lists. The test clock is Monday 5 October 2026. Each test is rolled back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithMockUser(username = "hr@holidays.kosh.test", roles = { "HR", "EMPLOYEE" })
class HolidayCalendarTest {

	private static final String FACTORY_WEEK = "SATURDAY:2,4 SUNDAY";

	private final MockMvc mvc;

	private final HolidayService service;

	private final OrgService org;

	private final AuditEventRepository events;

	private final JdbcTemplate jdbc;

	private final EntityManager entityManager;

	@Autowired
	HolidayCalendarTest(MockMvc mvc, HolidayService service, OrgService org, AuditEventRepository events,
			JdbcTemplate jdbc, EntityManager entityManager) {
		this.mvc = mvc;
		this.service = service;
		this.org = org;
		this.events = events;
		this.jdbc = jdbc;
		this.entityManager = entityManager;
	}

	// ------------------------------------------------------------------ the year page

	@Test
	void withoutWorkLocationsThePageSaysToAddOneFirst() throws Exception {
		String page = page(get("/hr/holidays").with(hr()), "hr/holidays/list");
		assertThat(page).contains("<title>Holidays | Kosh</title>")
			.contains("<a class=\"nav-link\" href=\"/hr/holidays\" title=\"Holidays\" aria-current=\"page\">")
			.contains("No work locations yet")
			.contains("href=\"/hr/org/locations/new\"")
			.doesNotContain("Add holiday");
	}

	@Test
	void theYearPageListsTheHolidaysWithTheWorkWeekAndTheWorkingDaysLeft() throws Exception {
		long shiroli = location("SHR", "Shiroli factory", true);
		service.setWorkWeek(shiroli, WeeklyOffs.parse(FACTORY_WEEK));
		add(shiroli, "2026-01-26", "Republic Day", HolidayType.NATIONAL);
		add(shiroli, "2026-03-19", "Gudi Padwa", HolidayType.FESTIVAL);
		add(shiroli, "2026-03-31", "Mahavir Jayanti", HolidayType.OPTIONAL);
		add(shiroli, "2026-05-01", "Maharashtra Day", HolidayType.NATIONAL);
		add(shiroli, "2026-08-15", "Independence Day", HolidayType.NATIONAL);
		add(shiroli, "2026-09-14", "Ganesh Chaturthi", HolidayType.FESTIVAL);
		add(shiroli, "2026-10-02", "Gandhi Jayanti", HolidayType.NATIONAL);
		add(shiroli, "2026-11-08", "Diwali (Lakshmi Puja)", HolidayType.FESTIVAL);

		String page = page(get("/hr/holidays").with(hr()), "hr/holidays/list");
		// 365 days - 52 Sundays - 24 2nd/4th Saturdays = 289; six closed holidays fall on working days (Diwali is a
		// Sunday, 15 August the 3rd Saturday); the optional one is a working day.
		assertThat(page).contains("2026 at Shiroli factory")
			.contains("7 holidays and 1 optional · 283 working days")
			.containsSubsequence("Republic Day", "Gudi Padwa", "Mahavir Jayanti", "Maharashtra Day", "Independence Day",
					"Ganesh Chaturthi", "Gandhi Jayanti", "Diwali (Lakshmi Puja)")
			.contains("<time class=\"cell-title\" datetime=\"2026-01-26\">26 Jan 2026</time>")
			.contains("<span class=\"cell-sub\">Monday</span>")
			.contains("<span class=\"badge\" data-status=\"NATIONAL\">National</span>")
			.contains("<span class=\"badge\" data-status=\"FESTIVAL\">Festival</span>")
			.contains("<span class=\"badge\" data-status=\"OPTIONAL\">Optional</span>")
			.contains("Off every Sunday and the 2nd and 4th Saturday")
			.contains("<dt>Saturday</dt>", "<dd>Off on the 2nd and 4th</dd>", "<dt>Sunday</dt>", "<dd>Off every week</dd>")
			.contains("href=\"/hr/holidays/work-week/" + shiroli + "?year=2026\"")
			.contains("href=\"/hr/holidays/new?location=" + shiroli + "&amp;year=2026\"")
			.doesNotContain("Copy 2025", "Check date");
		assertThat(page.split("On a weekly off", -1)).hasSize(2);
		assertThat(service.workingDaysBetween(shiroli, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)))
			.isEqualTo(283);
	}

	@Test
	void theFirstActiveLocationAndTheCurrentYearAreShownUnlessOthersAreChosen() throws Exception {
		long aundh = location("AUN", "Aundh store", false);
		long pune = location("PNQ", "Pune sales office", true);
		long shiroli = location("SHR", "Shiroli factory", true);
		add(shiroli, "2027-01-26", "Republic Day", HolidayType.NATIONAL);

		String first = page(get("/hr/holidays").with(hr()), "hr/holidays/list");
		assertThat(first).contains("2026 at Pune sales office")
			.contains("<option value=\"" + pune + "\" selected=\"selected\">Pune sales office</option>")
			.contains("<option value=\"" + shiroli + "\">Shiroli factory</option>")
			.doesNotContain("Aundh store")
			.contains("<option value=\"2026\" selected=\"selected\">2026</option>")
			.contains("<option value=\"2023\">2023</option>", "<option value=\"2028\">2028</option>")
			.contains("No holidays for 2026 yet");

		String chosen = page(get("/hr/holidays").queryParam("location", String.valueOf(shiroli))
			.queryParam("year", "2027")
			.with(hr()), "hr/holidays/list");
		assertThat(chosen).contains("2027 at Shiroli factory").contains("Republic Day");

		String inactive = page(get("/hr/holidays").queryParam("location", String.valueOf(aundh))
			.queryParam("year", "2019")
			.with(hr()), "hr/holidays/list");
		assertThat(inactive).contains("2019 at Aundh store")
			.contains("<option value=\"" + aundh + "\" selected=\"selected\">Aundh store (inactive)</option>")
			.contains("<option value=\"2019\" selected=\"selected\">2019</option>");

		for (String unreadable : List.of("abc", "1999", "2100", "")) {
			assertThat(page(get("/hr/holidays").queryParam("year", unreadable).with(hr()), "hr/holidays/list"))
				.contains("2026 at Pune sales office");
		}
		mvc.perform(get("/hr/holidays").queryParam("location", "999999").with(hr())).andExpect(status().isNotFound());
		mvc.perform(get("/hr/holidays").queryParam("location", "x").with(hr())).andExpect(status().isNotFound());
	}

	// ------------------------------------------------------------------ is it a working day?

	@Test
	void workingDaysFollowTheWeeklyOffsAndTheClosedHolidaysOfTheLocation() {
		long shiroli = location("SHR", "Shiroli factory", true);
		long pune = location("PNQ", "Pune sales office", true);
		service.setWorkWeek(shiroli, WeeklyOffs.parse(FACTORY_WEEK));
		add(shiroli, "2026-10-02", "Gandhi Jayanti", HolidayType.NATIONAL);
		add(shiroli, "2026-10-20", "Dussehra", HolidayType.FESTIVAL);
		add(shiroli, "2026-10-21", "Optional day", HolidayType.OPTIONAL);

		// The 2nd/4th Saturday rule in October 2026 (Saturdays 3, 10, 17, 24, 31).
		assertThat(service.isWorkingDay(shiroli, LocalDate.of(2026, 10, 3))).isTrue();
		assertThat(service.isWorkingDay(shiroli, LocalDate.of(2026, 10, 10))).isFalse();
		assertThat(service.isWorkingDay(shiroli, LocalDate.of(2026, 10, 17))).isTrue();
		assertThat(service.isWorkingDay(shiroli, LocalDate.of(2026, 10, 24))).isFalse();
		assertThat(service.isWorkingDay(shiroli, LocalDate.of(2026, 10, 31))).isTrue();
		assertThat(service.isWorkingDay(shiroli, LocalDate.of(2026, 10, 4))).isFalse();
		assertThat(service.isWorkingDay(shiroli, LocalDate.of(2026, 10, 5))).isTrue();
		// National and festival holidays close the location; an optional holiday does not.
		assertThat(service.isWorkingDay(shiroli, LocalDate.of(2026, 10, 2))).isFalse();
		assertThat(service.isWorkingDay(shiroli, LocalDate.of(2026, 10, 20))).isFalse();
		assertThat(service.isWorkingDay(shiroli, LocalDate.of(2026, 10, 21))).isTrue();
		// 31 - 4 Sundays - 2 Saturdays - 2 holidays.
		assertThat(service.workingDaysBetween(shiroli, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)))
			.isEqualTo(23);
		assertThat(service.workingDaysBetween(shiroli, LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 11)))
			.isEqualTo(5);

		// Pune has no work week set (Sunday off) and none of Shiroli's holidays.
		assertThat(service.isWorkingDay(pune, LocalDate.of(2026, 10, 2))).isTrue();
		assertThat(service.isWorkingDay(pune, LocalDate.of(2026, 10, 10))).isTrue();
		assertThat(service.workingDaysBetween(pune, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)))
			.isEqualTo(27);

		assertThatThrownBy(() -> service.workingDaysBetween(shiroli, LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 1)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.isWorkingDay(999999L, LocalDate.of(2026, 10, 2)))
			.isInstanceOf(NotFoundException.class);
	}

	@Test
	void anyCodeMayAskAboutWorkingDays() {
		long shiroli = location("SHR", "Shiroli factory", true);
		add(shiroli, "2026-10-02", "Gandhi Jayanti", HolidayType.NATIONAL);

		SecurityContextHolder.getContext()
			.setAuthentication(new TestingAuthenticationToken("employee", null, "ROLE_EMPLOYEE"));
		assertThat(service.isWorkingDay(shiroli, LocalDate.of(2026, 10, 2))).isFalse();
		SecurityContextHolder.clearContext();
		assertThat(service.workingDaysBetween(shiroli, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3)))
			.isEqualTo(2);
	}

	// ------------------------------------------------------------------ adding, editing, deleting

	@Test
	void addingAHolidayTidiesTheNameListsItAndAuditsIt() throws Exception {
		long shiroli = location("SHR", "Shiroli factory", true);

		String form = page(get("/hr/holidays/new").queryParam("location", String.valueOf(shiroli))
			.queryParam("year", "2027")
			.with(hr()), "hr/holidays/edit");
		assertThat(form).contains("<title>Add holiday | Kosh</title>")
			.contains("At Shiroli factory")
			.contains("<input type=\"hidden\" name=\"location\" value=\"" + shiroli + "\">")
			.contains("<input type=\"hidden\" name=\"year\" value=\"2027\">")
			.contains("type=\"date\"")
			.contains("Open as usual. Employees may take the day off as leave.")
			.contains("href=\"/hr/holidays?location=" + shiroli + "&amp;year=2027\"")
			.doesNotContain("Delete holiday");

		mvc.perform(post("/hr/holidays").with(hr())
			.with(csrf())
			.param("location", String.valueOf(shiroli))
			.param("year", "2027")
			.param("date", "2027-01-26")
			.param("name", "  Republic   Day ")
			.param("type", "NATIONAL"))
			.andExpect(redirectedUrl("/hr/holidays?location=" + shiroli + "&year=2027"))
			.andExpect(flash().attribute(Flash.ATTRIBUTE, Flash.success("Republic Day added")));

		long id = holidayId(shiroli, "2027-01-26");
		assertThat(jdbc.queryForObject("select name from holidays where id = ?", String.class, id))
			.isEqualTo("Republic Day");
		AuditEvent added = only(AuditAction.HOLIDAY_ADDED, Holiday.AUDIT_TYPE, id);
		assertThat(added.getSummary()).isEqualTo("Added holiday Republic Day on 26 Jan 2027 at Shiroli factory");
		assertThat(added.getAfterJson()).isEqualTo(
				"{\"Location\":\"Shiroli factory\",\"Date\":\"26 Jan 2027\",\"Name\":\"Republic Day\",\"Type\":\"National\"}");
		assertThat(page(get("/hr/holidays").queryParam("year", "2027").with(hr()), "hr/holidays/list"))
			.contains("Republic Day")
			.contains("1 holiday · ");
	}

	@Test
	void everyBrokenRuleShowsNextToItsFieldAndTheInputIsKept() throws Exception {
		long shiroli = location("SHR", "Shiroli factory", true);
		add(shiroli, "2027-01-26", "Republic Day", HolidayType.NATIONAL);

		String empty = page(post("/hr/holidays").with(hr())
			.with(csrf())
			.param("location", String.valueOf(shiroli))
			.param("year", "2027")
			.param("name", " "), "hr/holidays/edit");
		assertThat(empty).containsSubsequence("Enter the date.", "Enter the holiday’s name.", "Choose the type.")
			.contains("aria-invalid=\"true\"");

		String taken = page(add(shiroli, "2027-01-26", "Prajasattak Din", "FESTIVAL"), "hr/holidays/edit");
		assertThat(taken).contains("Republic Day is already on this date.").contains("value=\"Prajasattak Din\"");

		assertThat(page(add(shiroli, "1999-12-31", "Old", "FESTIVAL"), "hr/holidays/edit"))
			.contains("Enter a date from 2000 to 2099.");
		assertThat(page(add(shiroli, "26/01/2027", "Republic Day", "NATIONAL"), "hr/holidays/edit"))
			.contains("Enter a real date, like 26 January 2027.");
		assertThat(page(add(shiroli, "2027-08-15", "x".repeat(101), "NATIONAL"), "hr/holidays/edit"))
			.contains("Use at most 100 characters.");
		assertThat(page(add(shiroli, "2027-08-15", "Independence Day", "PUBLIC"), "hr/holidays/edit"))
			.contains("Choose a value from the list.");
		assertThat(count(shiroli)).isEqualTo(1);
	}

	@Test
	void editingSavesTheChangedFieldsAndAuditsThem() throws Exception {
		long shiroli = location("SHR", "Shiroli factory", true);
		long id = add(shiroli, "2026-12-25", "Christmas", HolidayType.OPTIONAL);
		add(shiroli, "2026-11-08", "Diwali", HolidayType.FESTIVAL);

		String edit = page(get("/hr/holidays/" + id).with(hr()), "hr/holidays/edit");
		assertThat(edit).contains("<title>Christmas | Kosh</title>")
			.contains("value=\"2026-12-25\"")
			.contains("Delete holiday")
			.contains("It is removed from the 2026 holidays at Shiroli factory. This can’t be undone.")
			.contains("Finalized payslips are not affected.")
			// An optional holiday is a working day already: the page must not promise deleting it changes that.
			.doesNotContain("counts as a working day again")
			.doesNotContain("check the date")
			.doesNotContain("name=\"location\"");

		mvc.perform(post("/hr/holidays/" + id).with(hr())
			.with(csrf())
			.param("date", "2026-12-25")
			.param("name", "Christmas Day")
			.param("type", "FESTIVAL"))
			.andExpect(redirectedUrl("/hr/holidays?location=" + shiroli + "&year=2026"))
			.andExpect(flash().attribute(Flash.ATTRIBUTE,
					Flash.success("Saved Christmas Day").withText("Changed: Name, Type.")));
		AuditEvent changed = only(AuditAction.HOLIDAY_CHANGED, Holiday.AUDIT_TYPE, id);
		assertThat(changed.getBeforeJson()).isEqualTo("{\"Name\":\"Christmas\",\"Type\":\"Optional\"}");
		assertThat(changed.getAfterJson()).isEqualTo("{\"Name\":\"Christmas Day\",\"Type\":\"Festival\"}");

		mvc.perform(post("/hr/holidays/" + id).with(hr())
			.with(csrf())
			.param("date", "2026-12-25")
			.param("name", "Christmas Day")
			.param("type", "FESTIVAL"))
			.andExpect(flash().attribute(Flash.ATTRIBUTE,
					Flash.info("Nothing to save").withText("Christmas Day is as it was.")));
		assertThat(events(AuditAction.HOLIDAY_CHANGED, Holiday.AUDIT_TYPE, id)).hasSize(1);

		String clash = page(post("/hr/holidays/" + id).with(hr())
			.with(csrf())
			.param("date", "2026-11-08")
			.param("name", "Christmas Day")
			.param("type", "FESTIVAL"), "hr/holidays/edit");
		assertThat(clash).contains("Diwali is already on this date.");

		mvc.perform(get("/hr/holidays/999999").with(hr())).andExpect(status().isNotFound());
		mvc.perform(post("/hr/holidays/999999").with(hr()).with(csrf()).param("name", "x"))
			.andExpect(status().isNotFound());
	}

	@Test
	void deletingAHolidayMakesTheDayAWorkingDayAgain() throws Exception {
		long shiroli = location("SHR", "Shiroli factory", true);
		long id = add(shiroli, "2026-10-20", "Dussehra", HolidayType.FESTIVAL);
		assertThat(service.isWorkingDay(shiroli, LocalDate.of(2026, 10, 20))).isFalse();

		mvc.perform(post("/hr/holidays/" + id + "/delete").with(hr()).with(csrf()))
			.andExpect(redirectedUrl("/hr/holidays?location=" + shiroli + "&year=2026"))
			.andExpect(flash().attribute(Flash.ATTRIBUTE, Flash.success("Dussehra deleted")));

		assertThat(count(shiroli)).isZero();
		assertThat(service.isWorkingDay(shiroli, LocalDate.of(2026, 10, 20))).isTrue();
		AuditEvent deleted = only(AuditAction.HOLIDAY_DELETED, Holiday.AUDIT_TYPE, id);
		assertThat(deleted.getSummary()).isEqualTo("Deleted holiday Dussehra on 20 Oct 2026 at Shiroli factory");
		assertThat(deleted.getBeforeJson()).contains("\"Name\":\"Dussehra\"");
		mvc.perform(post("/hr/holidays/" + id + "/delete").with(hr()).with(csrf())).andExpect(status().isNotFound());
	}

	@Test
	void aLocationWithAHolidayCalendarIsInUse() {
		long shiroli = location("SHR", "Shiroli factory", true);
		add(shiroli, "2026-10-20", "Dussehra", HolidayType.FESTIVAL);
		// A delete request has its own persistence context: the holiday is not loaded there, the foreign key refuses.
		entityManager.flush();
		entityManager.clear();
		assertThatThrownBy(() -> org.delete(OrgKind.LOCATIONS, shiroli)).isInstanceOf(BusinessRuleException.class)
			.hasMessage("Shiroli factory is in use, so it can’t be deleted");
	}

	// ------------------------------------------------------------------ copying last year's list

	@Test
	void copyingLastYearKeepsTheDatesSkipsWhatIsThereAndMarksMovingDatesToCheck() throws Exception {
		long shiroli = location("SHR", "Shiroli factory", true);
		add(shiroli, "2026-01-26", "Republic Day", HolidayType.NATIONAL);
		add(shiroli, "2026-03-31", "Mahavir Jayanti", HolidayType.OPTIONAL);
		add(shiroli, "2026-05-01", "Maharashtra Day", HolidayType.NATIONAL);
		add(shiroli, "2026-11-08", "Diwali", HolidayType.FESTIVAL);
		add(shiroli, "2027-01-26", "Republic Day", HolidayType.NATIONAL);
		add(shiroli, "2027-10-29", "diwali", HolidayType.FESTIVAL);

		mvc.perform(post("/hr/holidays/copy").with(hr())
			.with(csrf())
			.param("location", String.valueOf(shiroli))
			.param("year", "2027"))
			.andExpect(redirectedUrl("/hr/holidays?location=" + shiroli + "&year=2027"))
			.andExpect(flash().attribute(Flash.ATTRIBUTE, Flash.success("Copied 2 holidays from 2026")
				.withText("2 already in 2027 were skipped. Festival and optional dates usually move every year: check each one marked “Check date”.")));

		assertThat(jdbc.queryForList(
				"select concat(holiday_date, ' ', name, ' ', date_to_check) from holidays where location_id = ? and holiday_date >= '2027-01-01' order by holiday_date",
				String.class, shiroli))
			.containsExactly("2027-01-26 Republic Day FALSE", "2027-03-31 Mahavir Jayanti TRUE",
					"2027-05-01 Maharashtra Day FALSE", "2027-10-29 diwali FALSE");
		AuditEvent copied = only(AuditAction.HOLIDAYS_COPIED, OrgKind.LOCATIONS.label(), shiroli);
		assertThat(copied.getSummary()).isEqualTo("Copied 2 holidays from 2026 to 2027 at Shiroli factory");

		String page = page(get("/hr/holidays").queryParam("location", String.valueOf(shiroli))
			.queryParam("year", "2027")
			.with(hr()), "hr/holidays/list");
		assertThat(page).contains("1 copied date needs checking")
			.contains("<span class=\"badge\" data-status=\"TO_CHECK\">Check date</span>")
			.contains("Copy 2026’s holidays");

		mvc.perform(post("/hr/holidays/copy").with(hr())
			.with(csrf())
			.param("location", String.valueOf(shiroli))
			.param("year", "2027"))
			.andExpect(flash().attribute(Flash.ATTRIBUTE, Flash.info("Nothing to copy")
				.withText("Every 2026 holiday is already in 2027, on its date or by its name.")));
		assertThat(events(AuditAction.HOLIDAYS_COPIED, OrgKind.LOCATIONS.label(), shiroli)).hasSize(1);
	}

	@Test
	void savingACopiedHolidayConfirmsItsDate() throws Exception {
		long shiroli = location("SHR", "Shiroli factory", true);
		add(shiroli, "2026-11-08", "Diwali", HolidayType.FESTIVAL);
		service.copyPreviousYear(shiroli, 2027);
		long id = holidayId(shiroli, "2027-11-08");

		String edit = page(get("/hr/holidays/" + id).with(hr()), "hr/holidays/edit");
		assertThat(edit).contains("Copied from last year: check the date");

		mvc.perform(post("/hr/holidays/" + id).with(hr())
			.with(csrf())
			.param("date", "2027-11-08")
			.param("name", "Diwali")
			.param("type", "FESTIVAL"))
			.andExpect(flash().attribute(Flash.ATTRIBUTE, Flash.success("Date of Diwali confirmed")));
		assertThat(jdbc.queryForObject("select date_to_check from holidays where id = ?", Boolean.class, id)).isFalse();
		AuditEvent confirmed = only(AuditAction.HOLIDAY_CHANGED, Holiday.AUDIT_TYPE, id);
		assertThat(confirmed.getAfterJson()).isEqualTo("{\"Date confirmed\":\"Yes\"}");

		add(shiroli, "2026-03-19", "Gudi Padwa", HolidayType.FESTIVAL);
		asHr().copyPreviousYear(shiroli, 2027);
		long gudiPadwa = holidayId(shiroli, "2027-03-19");
		mvc.perform(post("/hr/holidays/" + gudiPadwa).with(hr())
			.with(csrf())
			.param("date", "2027-04-07")
			.param("name", "Gudi Padwa")
			.param("type", "FESTIVAL"))
			.andExpect(flash().attribute(Flash.ATTRIBUTE,
					Flash.success("Saved Gudi Padwa").withText("Changed: Date, Date confirmed.")));
	}

	@Test
	void copyingNeedsLastYearsListAndKeepsLeapDaysInTheMonth() throws Exception {
		long shiroli = location("SHR", "Shiroli factory", true);
		mvc.perform(post("/hr/holidays/copy").with(hr())
			.with(csrf())
			.param("location", String.valueOf(shiroli))
			.param("year", "2026"))
			.andExpect(redirectedUrl("/hr/holidays?location=" + shiroli + "&year=2026"))
			.andExpect(flash().attribute(Flash.ATTRIBUTE,
					Flash.danger("Shiroli factory has no holidays in 2025 to copy.")));

		add(shiroli, "2028-02-29", "Leap day", HolidayType.OPTIONAL);
		assertThat(asHr().copyPreviousYear(shiroli, 2029)).isEqualTo(new HolidayService.Copied(1, 0));
		assertThat(holidayId(shiroli, "2029-02-28")).isPositive();

		String empty = page(get("/hr/holidays").queryParam("year", "2027").with(hr()), "hr/holidays/list");
		assertThat(empty).contains("No holidays for 2027 yet")
			.contains("Add the national and festival holidays people at this location get off.");
		add(shiroli, "2026-01-26", "Republic Day", HolidayType.NATIONAL);
		String copyable = page(get("/hr/holidays").queryParam("year", "2027").with(hr()), "hr/holidays/list");
		assertThat(copyable).contains("Copy 2026’s list and check the festival dates, or add holidays one by one.")
			.contains("Copy 2026’s holiday<");
		assertThatThrownBy(() -> asHr().copyPreviousYear(shiroli, 2100)).isInstanceOf(BusinessRuleException.class)
			.hasMessage("Choose a year from 2000 to 2099.");
	}

	// ------------------------------------------------------------------ the work week

	@Test
	void theWorkWeekGridSavesTheTickedWeeksAndAuditsTheChange() throws Exception {
		long shiroli = location("SHR", "Shiroli factory", true);

		String grid = page(get("/hr/holidays/work-week/" + shiroli).with(hr()), "hr/holidays/work-week");
		assertThat(grid).contains("<title>Work week | Kosh</title>")
			.contains("Weekly offs at Shiroli factory")
			.contains("<th scope=\"col\">2nd</th>")
			.contains("<span class=\"visually-hidden\">2nd Saturday off</span>")
			.contains("value=\"SUNDAY:1\" checked=\"checked\"", "value=\"SUNDAY:5\" checked=\"checked\"")
			.doesNotContain("value=\"SATURDAY:2\" checked");

		mvc.perform(post("/hr/holidays/work-week/" + shiroli).with(hr())
			.with(csrf())
			.param("off", "SATURDAY:2", "SATURDAY:4", "SUNDAY:1", "SUNDAY:2", "SUNDAY:3", "SUNDAY:4", "SUNDAY:5"))
			.andExpect(redirectedUrl("/hr/holidays?location=" + shiroli + "&year=2026"))
			.andExpect(flash().attribute(Flash.ATTRIBUTE, Flash.success("Work week saved")
				.withText("Shiroli factory: Off every Sunday and the 2nd and 4th Saturday.")));
		assertThat(jdbc.queryForObject("select weekly_offs from work_weeks where location_id = ?", String.class, shiroli))
			.isEqualTo(FACTORY_WEEK);
		AuditEvent changed = only(AuditAction.WORK_WEEK_CHANGED, OrgKind.LOCATIONS.label(), shiroli);
		assertThat(changed.getSummary()).isEqualTo("Changed the work week at Shiroli factory");
		assertThat(changed.getBeforeJson()).isEqualTo("{\"Weekly offs\":\"Off every Sunday\"}");
		assertThat(changed.getAfterJson())
			.isEqualTo("{\"Weekly offs\":\"Off every Sunday and the 2nd and 4th Saturday\"}");
		assertThat(service.isWorkingDay(shiroli, LocalDate.of(2026, 10, 10))).isFalse();

		mvc.perform(post("/hr/holidays/work-week/" + shiroli).with(hr())
			.with(csrf())
			.param("off", "SUNDAY:1", "SUNDAY:2", "SUNDAY:3", "SUNDAY:4", "SUNDAY:5", "SATURDAY:4", "SATURDAY:2"))
			.andExpect(flash().attribute(Flash.ATTRIBUTE,
					Flash.info("Nothing to save").withText("The work week at Shiroli factory is as it was.")));
		assertThat(events(AuditAction.WORK_WEEK_CHANGED, OrgKind.LOCATIONS.label(), shiroli)).hasSize(1);

		// No box ticked: a seven-day week.
		mvc.perform(post("/hr/holidays/work-week/" + shiroli).with(hr()).with(csrf()))
			.andExpect(flash().attribute(Flash.ATTRIBUTE,
					Flash.success("Work week saved").withText("Shiroli factory: No weekly offs.")));
		assertThat(service.isWorkingDay(shiroli, LocalDate.of(2026, 10, 4))).isTrue();
	}

	@Test
	void theWorkWeekGoesBackToTheYearItWasOpenedFrom() throws Exception {
		long shiroli = location("SHR", "Shiroli factory", true);
		String back = "href=\"/hr/holidays?location=" + shiroli + "&amp;year=2027\"";

		String grid = page(get("/hr/holidays/work-week/" + shiroli).queryParam("year", "2027").with(hr()),
				"hr/holidays/work-week");
		assertThat(grid).contains("<input type=\"hidden\" name=\"year\" value=\"2027\">");
		assertThat(grid.split(Pattern.quote(back), -1)).hasSize(3); // the breadcrumb and Cancel

		mvc.perform(post("/hr/holidays/work-week/" + shiroli).with(hr())
			.with(csrf())
			.param("year", "2027")
			.param("off", "SATURDAY:2", "SUNDAY:1", "SUNDAY:2", "SUNDAY:3", "SUNDAY:4", "SUNDAY:5"))
			.andExpect(redirectedUrl("/hr/holidays?location=" + shiroli + "&year=2027"));
		assertThat(page(post("/hr/holidays/work-week/" + shiroli).with(hr())
			.with(csrf())
			.param("year", "2027")
			.param("off", "FUNDAY:1"), "hr/holidays/work-week")).contains(back)
			.contains("<input type=\"hidden\" name=\"year\" value=\"2027\">");

		// An unreadable or out-of-range year goes back to the current one, as on the list.
		mvc.perform(post("/hr/holidays/work-week/" + shiroli).with(hr()).with(csrf()).param("year", "2100"))
			.andExpect(redirectedUrl("/hr/holidays?location=" + shiroli + "&year=2026"));
		assertThat(page(get("/hr/holidays/work-week/" + shiroli).queryParam("year", "abc").with(hr()),
				"hr/holidays/work-week"))
			.contains("href=\"/hr/holidays?location=" + shiroli + "&amp;year=2026\"");
	}

	@Test
	void aWeekWithoutAWorkingDayOrAnUnknownBoxIsRefused() throws Exception {
		long shiroli = location("SHR", "Shiroli factory", true);
		List<String> all = new ArrayList<>();
		for (DayOfWeek day : DayOfWeek.values()) {
			for (int week = 1; week <= 4; week++) {
				all.add(day.name() + ":" + week);
			}
		}
		String refused = page(post("/hr/holidays/work-week/" + shiroli).with(hr())
			.with(csrf())
			.param("off", all.toArray(String[]::new)), "hr/holidays/work-week");
		assertThat(refused).contains("Leave at least one working day in every week of the month.")
			.contains("value=\"MONDAY:1\" checked=\"checked\"")
			.doesNotContain("value=\"MONDAY:5\" checked");

		String unknown = page(post("/hr/holidays/work-week/" + shiroli).with(hr())
			.with(csrf())
			.param("off", "FUNDAY:1"), "hr/holidays/work-week");
		assertThat(unknown).contains("Choose the weekly offs from the table.");
		assertThat(jdbc.queryForObject("select count(*) from work_weeks where location_id = ?", Long.class, shiroli))
			.isZero();
		assertThatThrownBy(() -> asHr().setWorkWeek(shiroli,
				WeeklyOffs.parse("MONDAY TUESDAY WEDNESDAY THURSDAY FRIDAY SATURDAY SUNDAY")))
			.isInstanceOfSatisfying(BusinessRuleException.class, rule -> assertThat(rule.getField()).isEqualTo("off"));
		mvc.perform(get("/hr/holidays/work-week/999999").with(hr())).andExpect(status().isNotFound());
	}

	// ------------------------------------------------------------------ who gets in

	@ParameterizedTest
	@ValueSource(strings = { "PAYROLL", "FINANCE", "MANAGER", "EMPLOYEE" })
	void onlyHrAndAdminsGetIn(String role) throws Exception {
		long shiroli = location("SHR", "Shiroli factory", true);
		long id = add(shiroli, "2026-10-20", "Dussehra", HolidayType.FESTIVAL);
		RequestPostProcessor other = user("other@holidays.kosh.test").roles(role, "EMPLOYEE");
		for (String path : List.of("/hr/holidays", "/hr/holidays/new?location=" + shiroli, "/hr/holidays/" + id,
				"/hr/holidays/work-week/" + shiroli)) {
			mvc.perform(get(path).with(other)).andExpect(status().isForbidden());
		}
		mvc.perform(post("/hr/holidays/" + id + "/delete").with(other).with(csrf())).andExpect(status().isForbidden());
		mvc.perform(post("/hr/holidays/copy").with(other)
			.with(csrf())
			.param("location", String.valueOf(shiroli))
			.param("year", "2027")).andExpect(status().isForbidden());
		mvc.perform(post("/hr/holidays/work-week/" + shiroli).with(other).with(csrf()))
			.andExpect(status().isForbidden());
		mvc.perform(post("/hr/holidays").with(other)
			.with(csrf())
			.param("location", String.valueOf(shiroli))
			.param("year", "2026")
			.param("date", "2026-11-08")
			.param("name", "Diwali")
			.param("type", "FESTIVAL")).andExpect(status().isForbidden());
		mvc.perform(post("/hr/holidays/" + id).with(other)
			.with(csrf())
			.param("date", "2026-10-21")
			.param("name", "Changed")
			.param("type", "OPTIONAL")).andExpect(status().isForbidden());
		assertThat(count(shiroli)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select concat(holiday_date, ' ', name, ' ', holiday_type) from holidays where id = ?",
				String.class, id))
			.isEqualTo("2026-10-20 Dussehra FESTIVAL");
	}

	@Test
	void adminsGetInAndAnonymousVisitorsAreSentToSignIn() throws Exception {
		long shiroli = location("SHR", "Shiroli factory", true);
		mvc.perform(get("/hr/holidays").with(user("admin@holidays.kosh.test").roles("ADMIN")))
			.andExpect(status().isOk());
		mvc.perform(get("/hr/holidays").with(anonymous()))
			.andExpect(redirectedUrl("/login"));
		mvc.perform(post("/hr/holidays").with(anonymous())
			.with(csrf())
			.param("location", String.valueOf(shiroli))
			.param("date", "2026-10-20")
			.param("name", "Dussehra")
			.param("type", "FESTIVAL")).andExpect(redirectedUrl("/login"));
		assertThat(count(shiroli)).isZero();
	}

	@Test
	@WithMockUser(username = "payroll@holidays.kosh.test", roles = { "PAYROLL", "FINANCE", "MANAGER", "EMPLOYEE" })
	void theServiceRefusesEveryoneButHrAndAdminsTheLists() {
		Details details = new Details(LocalDate.of(2026, 10, 20), "Dussehra", HolidayType.FESTIVAL);
		assertThatThrownBy(() -> service.locations()).isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> service.year(1L, 2026)).isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> service.create(1L, details)).isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> service.update(1L, details)).isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> service.delete(1L)).isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> service.copyPreviousYear(1L, 2027)).isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> service.setWorkWeek(1L, WeeklyOffs.DEFAULT)).isInstanceOf(AccessDeniedException.class);
	}

	@Test
	void theServiceChecksTheRulesWhateverTheCaller() {
		long shiroli = location("SHR", "Shiroli factory", true);
		assertThat(new Details(null, "  Holi \t Day ", null).name()).isEqualTo("Holi Day");
		assertRefused(() -> service.create(shiroli, new Details(null, "Holi", HolidayType.FESTIVAL)), "date");
		assertRefused(() -> service.create(shiroli, new Details(LocalDate.of(2026, 3, 4), " ", HolidayType.FESTIVAL)),
				"name");
		assertRefused(() -> service.create(shiroli, new Details(LocalDate.of(2026, 3, 4), "Holi", null)), "type");
		assertRefused(() -> service.create(shiroli, new Details(LocalDate.of(2100, 3, 4), "Holi", HolidayType.FESTIVAL)),
				"date");
		assertThatThrownBy(() -> service.create(999999L, new Details(LocalDate.of(2026, 3, 4), "Holi", HolidayType.FESTIVAL)))
			.isInstanceOf(NotFoundException.class);
		assertThat(count(shiroli)).isZero();
	}

	// ------------------------------------------------------------------ helpers

	static RequestPostProcessor hr() {
		return user("hr@holidays.kosh.test").roles("HR", "EMPLOYEE");
	}

	/** A work location in Maharashtra, straight into the table (the org pages are tested on their own). */
	private long location(String code, String name, boolean active) {
		jdbc.update("""
				insert into work_locations (code, name, active, address_line1, city, state_code, pincode, pt_applicable,
				    created_at, updated_at, version)
				values (?, ?, ?, 'Plot C-14, MIDC Shiroli', 'Kolhapur', 'MH', '416122', true, now(), now(), 0)""", code,
				name, active);
		return jdbc.queryForObject("select id from work_locations where code = ?", Long.class, code);
	}

	/**
	 * The service as the class's HR user. A MockMvc request clears the test thread's security context when it ends,
	 * so a service call after one would otherwise run signed out.
	 */
	private HolidayService asHr() {
		SecurityContextHolder.getContext()
			.setAuthentication(new TestingAuthenticationToken("hr@holidays.kosh.test", null, "ROLE_HR", "ROLE_EMPLOYEE"));
		return service;
	}

	private long add(long locationId, String date, String name, HolidayType type) {
		return asHr().create(locationId, new Details(LocalDate.parse(date), name, type));
	}

	/** The add form's POST, for tests of its errors. */
	private RequestBuilder add(long locationId, String date, String name, String type) {
		return post("/hr/holidays").with(hr())
			.with(csrf())
			.param("location", String.valueOf(locationId))
			.param("year", "2027")
			.param("date", date)
			.param("name", name)
			.param("type", type);
	}

	private long holidayId(long locationId, String date) {
		entityManager.flush();
		return jdbc.queryForObject("select id from holidays where location_id = ? and holiday_date = ?", Long.class,
				locationId, LocalDate.parse(date));
	}

	private long count(long locationId) {
		entityManager.flush();
		return jdbc.queryForObject("select count(*) from holidays where location_id = ?", Long.class, locationId);
	}

	private String page(RequestBuilder request, String view) throws Exception {
		return mvc.perform(request)
			.andExpect(status().isOk())
			.andExpect(view().name(view))
			.andReturn()
			.getResponse()
			.getContentAsString();
	}

	private List<AuditEvent> events(AuditAction action, String type, long id) {
		return events.findAll()
			.stream()
			.filter(event -> event.getAction() == action && type.equals(event.getEntityType())
					&& Long.valueOf(id).equals(event.getEntityId()))
			.toList();
	}

	private AuditEvent only(AuditAction action, String type, long id) {
		List<AuditEvent> found = events(action, type, id);
		assertThat(found).hasSize(1);
		return found.getFirst();
	}

	private static void assertRefused(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String field) {
		assertThatThrownBy(call).isInstanceOfSatisfying(BusinessRuleException.class,
				refused -> assertThat(refused.getField()).isEqualTo(field));
	}

}
