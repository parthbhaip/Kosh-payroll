package com.kosh.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.kosh.security.KoshUserDetails;
import com.kosh.user.Role;
import com.kosh.user.User;

/**
 * The audit log (PLAN T10): recording with actor, IP and masked before/after values, the {@code /admin/audit} page
 * with its filters and paging, the CSV export, and that only admins read any of it. Each test runs in a rolled-back
 * transaction; other tests may have committed events to the shared database, so every check here filters on actors or
 * summaries of its own.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AuditLogTest {

	/** The fixed test clock. */
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 5, 10, 0);

	private static final String ADMIN = "admin@audit.kosh.test";

	private final MockMvc mvc;

	private final AuditService audit;

	private final AuditEventRepository events;

	@Autowired
	AuditLogTest(MockMvc mvc, AuditService audit, AuditEventRepository events) {
		this.mvc = mvc;
		this.audit = audit;
		this.events = events;
	}

	@AfterEach
	void forgetRequestAndUser() {
		RequestContextHolder.resetRequestAttributes();
		SecurityContextHolder.clearContext();
	}

	// ------------------------------------------------------------------ recording

	@Test
	void anEventKeepsTheSignedInUserTheRequestIpAndOnlyTheChangedFieldsMasked() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setRemoteAddr("203.0.113.7");
		RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
		User meera = new User("Meera.Deshpande@Audit.Kosh.Test", "Meera Deshpande", Set.of(Role.HR, Role.EMPLOYEE));
		meera.setPasswordHash("{bcrypt}unused");
		KoshUserDetails signedIn = new KoshUserDetails(meera, false);
		SecurityContextHolder.getContext()
			.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(signedIn, null, signedIn.getAuthorities()));

		audit.record(AuditAction.ROLES_CHANGED, "User", 42L, "Changed roles for Rohan Kale", AuditChanges.create()
			.field("Roles", List.of("HR"), List.of("HR", "Payroll"))
			.field("Name", "Rohan Kale", "Rohan Kale")
			.pan("PAN", "ABCDE1234F", "ABCDE9876G")
			.account("Bank account", "001234567894321", "009876543210987"));

		AuditEvent event = only("Changed roles for Rohan Kale");
		assertThat(event.getOccurredAt()).isEqualTo(NOW);
		assertThat(event.getActorEmail()).isEqualTo("meera.deshpande@audit.kosh.test");
		assertThat(event.getActorName()).isEqualTo("Meera Deshpande");
		assertThat(event.getAction()).isEqualTo(AuditAction.ROLES_CHANGED);
		assertThat(event.getEntityType()).isEqualTo("User");
		assertThat(event.getEntityId()).isEqualTo(42L);
		assertThat(event.getIpAddress()).isEqualTo("203.0.113.7");
		assertThat(event.getBeforeJson()).isEqualTo("{\"Roles\":\"HR\",\"PAN\":\"ABXXXXX4F\",\"Bank account\":\"XXXXXX4321\"}");
		assertThat(event.getAfterJson())
			.isEqualTo("{\"Roles\":\"HR, Payroll\",\"PAN\":\"ABXXXXX6G\",\"Bank account\":\"XXXXXX0987\"}");
	}

	@Test
	void withNobodySignedInAndNoRequestTheActorIsSystem() {
		// The test framework provides a mock request to every test; startup work has none.
		RequestContextHolder.resetRequestAttributes();
		audit.record(AuditAction.ADMIN_BOOTSTRAPPED, "User", 7L, "Startup work with nobody signed in");

		AuditEvent event = only("Startup work with nobody signed in");
		assertThat(event.getActorName()).isEqualTo("System");
		assertThat(event.getActorEmail()).isNull();
		assertThat(event.getIpAddress()).isNull();
		assertThat(event.getBeforeJson()).isNull();
		assertThat(event.getAfterJson()).isNull();
	}

	@Test
	@Transactional(readOnly = true)
	void recordingInsideAReadOnlyTransactionFailsOnEveryDatabase() {
		// MySQL refuses the insert in a read-only transaction and H2 does not, so the service refuses it first.
		assertThatThrownBy(() -> audit.record(AuditAction.AUDIT_EXPORTED, null, null, "Inside a read-only read"))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void anActorIsKeptTidyAndWithinItsColumns() {
		audit.recordAs(new AuditService.Actor("  Priya.Joshi@Audit.Kosh.Test ", "  "), AuditAction.LOGIN_FAILED, null,
				null, "s".repeat(400), null);

		AuditEvent event = events.findAll(AuditEventRepository.matching(
				new AuditFilter("priya.joshi@audit", null, null, null, null, null), null)).getFirst();
		assertThat(event.getActorEmail()).isEqualTo("priya.joshi@audit.kosh.test");
		assertThat(event.getActorName()).isEqualTo("Unknown");
		assertThat(event.getSummary()).hasSize(300).endsWith("…");
	}

	// ------------------------------------------------------------------ page

	@Test
	void thePageListsEventsNewestFirstWithWhoWhatWhenWhereAndTheChanges() throws Exception {
		save(NOW.minusDays(1), "asha.patil@page.audit.kosh.test", "Asha Patil", AuditAction.USER_INVITED, "User", 12L,
				"Invited Rohan Kale (rohan@page.audit.kosh.test)", null, null, "198.51.100.20");
		save(NOW, "asha.patil@page.audit.kosh.test", "Asha Patil", AuditAction.ROLES_CHANGED, "User", 12L,
				"Changed roles for Rohan Kale", "{\"Roles\":\"Payroll, Employee\",\"PAN\":\"ABXXXXX4F\",\"Name\":null}",
				"{\"Roles\":\"HR, Employee\",\"PAN\":\"ABXXXXX6G\",\"Name\":\"Rohan <b>Kale</b>\"}", "203.0.113.7");
		save(NOW, null, "Unknown user", AuditAction.LOGIN_FAILED, null, null, "No account with this email", null, null,
				null);

		String page = page(get("/admin/audit").queryParam("actor", "page.audit"));

		assertThat(page).contains("<title>Audit log | Kosh</title>")
			.contains("<h1 class=\"page-title\">Audit log</h1>")
			.contains("<li><a href=\"/admin\">Admin</a></li>")
			.contains("<a class=\"nav-link\" href=\"/admin/audit\" title=\"Audit log\" aria-current=\"page\">")
			.contains("<caption class=\"visually-hidden\">Audit events, newest first</caption>")
			.contains("<th scope=\"col\">When</th>")
			.contains("<th scope=\"col\">IP address</th>")
			.contains("<th scope=\"row\"><time datetime=\"2026-10-05T10:00\">5 Oct 2026, 10:00 AM</time></th>")
			.contains("<span class=\"cell-title\">Asha Patil</span>")
			.contains("<span class=\"cell-sub\">asha.patil@page.audit.kosh.test</span>")
			.contains("<span class=\"badge badge-info\">Roles changed</span>")
			.contains("<span class=\"badge badge-info\">User invited</span>")
			.contains("<span>User</span>")
			.contains("<span class=\"mono text-muted\">#12</span>")
			.contains("<p class=\"audit-summary\">Changed roles for Rohan Kale</p>")
			.contains("<span>3 fields changed</span>")
			.containsPattern("<th scope=\"row\">Roles</th>\\s*<td>Payroll, Employee</td>\\s*<td>HR, Employee</td>")
			.containsPattern("<th scope=\"row\">PAN</th>\\s*<td>ABXXXXX4F</td>\\s*<td>ABXXXXX6G</td>")
			// An empty value shows a dash; text is escaped, never markup.
			.containsPattern("<th scope=\"row\">Name</th>\\s*<td>—</td>\\s*<td>Rohan &lt;b&gt;Kale&lt;/b&gt;</td>")
			.contains("<td class=\"mono\">203.0.113.7</td>")
			.containsPattern("Showing <strong>1–2</strong>\\s+of <strong>2</strong> <span>events</span>")
			.contains("<form method=\"post\" action=\"/admin/audit/export?actor=page.audit\">")
			.doesNotContain("No account with this email");
		assertThat(page.indexOf("Changed roles for Rohan Kale")).isLessThan(page.indexOf("Invited Rohan Kale"));
		// The invite changed no fields, so it has no "fields changed" toggle.
		assertThat(page).containsOnlyOnce("fields changed</span>");
	}

	@Test
	void filtersNarrowByActorActionRecordAndDateRange() throws Exception {
		save(LocalDateTime.of(2026, 10, 1, 23, 59), "asha@filters.audit.kosh.test", "Asha Patil", AuditAction.USER_DISABLED,
				"User", 21L, "Event one", null, null, null);
		save(LocalDateTime.of(2026, 10, 3, 0, 0), "rohan@filters.audit.kosh.test", "Ishaan Varerkar",
				AuditAction.ROLES_CHANGED, "User", 22L, "Event two", null, null, null);
		save(LocalDateTime.of(2026, 10, 5, 9, 30), "rohan@filters.audit.kosh.test", "Ishaan Varerkar",
				AuditAction.ROLES_CHANGED, "Payroll run", 22L, "Event three", null, null, null);

		assertThat(shown(get("/admin/audit").queryParam("actor", "filters.audit"))).containsExactly("Event three", "Event two",
				"Event one");
		// Actor matches the name in any case, or the e-mail.
		assertThat(shown(get("/admin/audit").queryParam("actor", "ISHAAN VARERKAR"))).containsExactly("Event three", "Event two");
		assertThat(shown(get("/admin/audit").queryParam("actor", "asha@filters"))).containsExactly("Event one");
		assertThat(shown(get("/admin/audit").queryParam("actor", "filters.audit").queryParam("action", "USER_DISABLED")))
			.containsExactly("Event one");
		assertThat(shown(get("/admin/audit").queryParam("actor", "filters.audit").queryParam("entity", "User")))
			.containsExactly("Event two", "Event one");
		assertThat(shown(get("/admin/audit").queryParam("actor", "filters.audit").queryParam("entity", "User").queryParam("entityId", "22")))
			.containsExactly("Event two");
		// The range is inclusive, whole days in IST.
		assertThat(shown(get("/admin/audit").queryParam("actor", "filters.audit").queryParam("from", "2026-10-02").queryParam("to", "2026-10-03")))
			.containsExactly("Event two");
		assertThat(shown(get("/admin/audit").queryParam("actor", "filters.audit").queryParam("to", "2026-10-01")))
			.containsExactly("Event one");
		assertThat(shown(get("/admin/audit").queryParam("actor", "filters.audit").queryParam("from", "2026-10-05")))
			.containsExactly("Event three");
		// Values that no control sends are ignored rather than failing the page.
		assertThat(shown(get("/admin/audit").queryParam("actor", "filters.audit")
			.queryParam("action", "DROP_TABLE")
			.queryParam("entityId", "abc")
			.queryParam("from", "yesterday"))).containsExactly("Event three", "Event two", "Event one");

		String page = page(get("/admin/audit").queryParam("actor", "rohan@filters")
			.queryParam("action", "ROLES_CHANGED")
			.queryParam("entity", "Payroll run")
			.queryParam("entityId", "22")
			.queryParam("from", "2026-10-01")
			.queryParam("to", "2026-10-05"));
		// The filter bar keeps what was chosen.
		assertThat(page).contains("value=\"rohan@filters\"")
			.contains("<option value=\"ROLES_CHANGED\" selected=\"selected\">Roles changed</option>")
			.contains("<option value=\"Payroll run\" selected=\"selected\">Payroll run</option>")
			.contains("name=\"entityId\" type=\"text\" inputmode=\"numeric\" pattern=\"[0-9]*\"")
			.contains("value=\"22\"")
			.contains("name=\"from\" type=\"date\" value=\"2026-10-01\"")
			.contains("name=\"to\" type=\"date\" value=\"2026-10-05\"")
			.contains("<a class=\"btn btn-ghost\" href=\"/admin/audit\">Clear</a>")
			.contains("action=\"/admin/audit/export?actor=rohan@filters&amp;action=ROLES_CHANGED&amp;entity=Payroll%20run"
					+ "&amp;entityId=22&amp;from=2026-10-01&amp;to=2026-10-05\">");
		for (AuditAction action : AuditAction.values()) {
			assertThat(page).contains(">" + action.label() + "</option>");
		}
	}

	@Test
	void aRangeThatEndsBeforeItStartsIsExplainedInsteadOfShowingNothing() throws Exception {
		save(NOW, "asha@range.audit.kosh.test", "Asha Patil", AuditAction.USER_ENABLED, "User", 1L, "Range event", null,
				null, null);

		String page = page(get("/admin/audit").queryParam("actor", "range.audit").queryParam("from", "2026-10-05").queryParam("to", "2026-10-01"));

		assertThat(page).contains("aria-invalid=\"true\" aria-describedby=\"audit-to-error\"")
			.containsPattern("<p class=\"error-text\" id=\"audit-to-error\">\\s*<svg[^>]*>.*?</svg><span>The end date is "
					+ "before the start date.</span>")
			.contains("No events to show")
			.doesNotContain("Range event")
			.doesNotContain("Export CSV")
			.doesNotContain("class=\"pagination\"");
		mvc.perform(post("/admin/audit/export").queryParam("from", "2026-10-05").queryParam("to", "2026-10-01").with(admin()).with(csrf()))
			.andExpect(redirectedUrl("/admin/audit?from=2026-10-05&to=2026-10-01"));
	}

	@Test
	void filtersThatMatchNothingSayHowToWidenThem() throws Exception {
		String page = page(get("/admin/audit").queryParam("actor", "nobody-has-this-name"));

		assertThat(page).contains("No events match these filters")
			.contains("Try a wider date range, another action, or clear the filters.")
			.contains("<a class=\"btn btn-secondary\" href=\"/admin/audit\">Clear filters</a>")
			.doesNotContain("<table class=\"table audit-table\">")
			.doesNotContain("Export CSV");
	}

	@Test
	void pagesHoldFiftyEvents() throws Exception {
		List<AuditEvent> many = new ArrayList<>();
		for (int i = 1; i <= 51; i++) {
			many.add(event(NOW, "pager@audit.kosh.test", "Pager", AuditAction.USER_UPDATED, "User", (long) i,
					"Paged event %02d".formatted(i), null, null, null));
		}
		events.saveAll(many);

		String first = page(get("/admin/audit").queryParam("actor", "pager@audit"));
		assertThat(Pattern.compile("Paged event \\d\\d").matcher(first).results().count()).isEqualTo(50);
		assertThat(first).contains("Paged event 51").doesNotContain("Paged event 01")
			.containsPattern("Showing <strong>1–50</strong>\\s+of <strong>51</strong> <span>events</span>")
			.contains("href=\"/admin/audit?actor=pager@audit&amp;page=1\" rel=\"next\"");

		String second = page(get("/admin/audit").queryParam("actor", "pager@audit").queryParam("page", "1"));
		assertThat(Pattern.compile("Paged event \\d\\d").matcher(second).results().count()).isEqualTo(1);
		assertThat(second).contains("Paged event 01")
			.containsPattern("Showing <strong>51–51</strong>\\s+of <strong>51</strong> <span>events</span>")
			// The export form drops the page: it always covers every matching event.
			.contains("action=\"/admin/audit/export?actor=pager@audit\">");
	}

	// ------------------------------------------------------------------ export

	@Test
	void exportDownloadsTheFilteredEventsAsCsvAndIsItselfRecorded() throws Exception {
		save(NOW.minusMinutes(5), "asha@csv.audit.kosh.test", "Patil, Asha", AuditAction.ROLES_CHANGED, "User", 12L,
				"Changed roles for Rohan Kale", "{\"Roles\":\"HR\",\"Name\":null}",
				"{\"Roles\":\"HR, Payroll\",\"Name\":\"Rohan \\\"RK\\\" Kale\"}", "203.0.113.7");
		save(NOW, "mallory@csv.audit.kosh.test", "=cmd|' /C calc'!A0", AuditAction.LOGIN_FAILED, null, null,
				"@SUM(1+1)", null, null, null);
		save(NOW, "asha@other.audit.kosh.test", "Asha Patil", AuditAction.USER_ENABLED, "User", 13L, "Not in this file",
				null, null, null);

		MockHttpServletResponse response = mvc.perform(post("/admin/audit/export").queryParam("actor", "csv.audit").with(admin()).with(csrf()))
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
			.andExpect(header().string("Content-Disposition", "attachment; filename=\"kosh-audit-log-2026-10-05.csv\""))
			.andReturn()
			.getResponse();
		String csv = new String(response.getContentAsByteArray(), StandardCharsets.UTF_8);

		assertThat(csv).isEqualTo("﻿"
				+ "Time (IST),Actor,Actor email,Action,Record,Record ID,Summary,Changes,IP address\r\n"
				+ "2026-10-05 10:00:00,'=cmd|' /C calc'!A0,mallory@csv.audit.kosh.test,Sign-in failed,,,'@SUM(1+1),,\r\n"
				+ "2026-10-05 09:55:00,\"Patil, Asha\",asha@csv.audit.kosh.test,Roles changed,User,12,"
				+ "Changed roles for Rohan Kale,\"Roles: HR → HR, Payroll; Name: — → Rohan \"\"RK\"\" Kale\",203.0.113.7\r\n");

		AuditEvent exported = only("Exported the audit log: actor “csv.audit”");
		assertThat(exported.getAction()).isEqualTo(AuditAction.AUDIT_EXPORTED);
		assertThat(exported.getActorEmail()).isEqualTo(ADMIN);
		assertThat(exported.getIpAddress()).isEqualTo("127.0.0.1");
	}

	@Test
	void anUnfilteredExportLeavesOutItsOwnEvent() throws Exception {
		String csv = mvc.perform(post("/admin/audit/export").with(admin()).with(csrf()))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);

		assertThat(only("Exported the audit log: all events").getAction()).isEqualTo(AuditAction.AUDIT_EXPORTED);
		assertThat(csv).doesNotContain("Exported the audit log: all events");
	}

	@Test
	void theExportIsAPostWithTheCsrfTokenSoAnotherSiteCannotAddEvents() throws Exception {
		mvc.perform(get("/admin/audit/export").queryParam("actor", "forged.audit").with(admin()))
			.andExpect(status().isMethodNotAllowed());
		// Without the token it is a stale form (SecurityConfig): sent to /login?expired, nothing exported.
		mvc.perform(post("/admin/audit/export").queryParam("actor", "forged.audit").with(admin()))
			.andExpect(redirectedUrl("/login?expired"));

		assertThat(events.findAll()).noneMatch(event -> event.getAction() == AuditAction.AUDIT_EXPORTED);
	}

	@Test
	void theExportReadsInBatchesAndMissesNothing() throws Exception {
		int total = AuditService.EXPORT_BATCH * 2 + 1;
		List<AuditEvent> many = new ArrayList<>();
		for (int i = 1; i <= total; i++) {
			many.add(event(NOW, "batch@audit.kosh.test", "Batch", AuditAction.USER_UPDATED, "User", (long) i,
					"Batch event %04d".formatted(i), null, null, null));
		}
		events.saveAll(many);
		SecurityContextHolder.getContext()
			.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(ADMIN, null,
					List.of(() -> "ROLE_ADMIN")));
		StringWriter out = new StringWriter();

		audit.export(new AuditFilter("batch@audit", null, null, null, null, null), out);

		List<String> lines = out.toString().lines().skip(1).toList();
		assertThat(lines).hasSize(total);
		assertThat(lines.getFirst()).contains("Batch event %04d".formatted(total));
		assertThat(lines.getLast()).contains("Batch event 0001");
		assertThat(lines.stream().distinct()).hasSize(total);
	}

	// ------------------------------------------------------------------ who may read it

	@ParameterizedTest
	@ValueSource(strings = { "HR", "PAYROLL", "FINANCE", "MANAGER", "EMPLOYEE" })
	void onlyAdminsReadOrExportTheAuditLog(String role) throws Exception {
		mvc.perform(get("/admin/audit").with(user("someone@audit.kosh.test").roles(role, "EMPLOYEE")))
			.andExpect(status().isForbidden());
		mvc.perform(post("/admin/audit/export").with(user("someone@audit.kosh.test").roles(role, "EMPLOYEE")).with(csrf()))
			.andExpect(status().isForbidden());
	}

	@Test
	void anonymousVisitorsAreSentToSignIn() throws Exception {
		mvc.perform(get("/admin/audit")).andExpect(redirectedUrl("/login"));
		mvc.perform(post("/admin/audit/export").with(csrf())).andExpect(redirectedUrl("/login"));
	}

	@Test
	@WithMockUser(username = "hr@audit.kosh.test", roles = { "HR", "EMPLOYEE" })
	void theServiceChecksTheRoleAgain() {
		assertThatThrownBy(() -> audit.list(AuditFilter.NONE, PageRequest.of(0, 50)))
			.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> audit.entityTypes()).isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> audit.export(AuditFilter.NONE, new StringWriter()))
			.isInstanceOf(AccessDeniedException.class);
		// Anyone's actions are recorded, whatever their role.
		audit.record(AuditAction.PASSWORD_CHANGED, "User", 5L, "Recorded for an HR user");
		assertThat(only("Recorded for an HR user").getActorEmail()).isEqualTo("hr@audit.kosh.test");
	}

	// ------------------------------------------------------------------ helpers

	private AuditEvent only(String summary) {
		List<AuditEvent> found = events.findAll().stream().filter(event -> event.getSummary().equals(summary)).toList();
		assertThat(found).as("events with summary %s", summary).hasSize(1);
		return found.getFirst();
	}

	private void save(LocalDateTime at, String email, String name, AuditAction action, String entityType, Long entityId,
			String summary, String before, String after, String ip) {
		events.save(event(at, email, name, action, entityType, entityId, summary, before, after, ip));
	}

	private static AuditEvent event(LocalDateTime at, String email, String name, AuditAction action, String entityType,
			Long entityId, String summary, String before, String after, String ip) {
		return new AuditEvent(at, email, name, action, entityType, entityId, summary, before, after, ip);
	}

	/** The summaries on the page, top to bottom. */
	private List<String> shown(MockHttpServletRequestBuilder request) throws Exception {
		return Pattern.compile("<p class=\"audit-summary\">([^<]*)</p>")
			.matcher(page(request))
			.results()
			.map(match -> match.group(1))
			.toList();
	}

	private String page(MockHttpServletRequestBuilder request) throws Exception {
		return mvc.perform(request.with(admin()))
			.andExpect(status().isOk())
			.andExpect(view().name("admin/audit/list"))
			.andReturn()
			.getResponse()
			.getContentAsString();
	}

	private static RequestPostProcessor admin() {
		return user(ADMIN).roles("ADMIN");
	}

}
