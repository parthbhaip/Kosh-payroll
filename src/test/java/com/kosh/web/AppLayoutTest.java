package com.kosh.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.kosh.security.SecurityConfig;

/**
 * The app layout around a page, rendered on test-only pages that sit on real menu paths ({@link ShellTestPages}):
 * sidebar by role with the active item, top bar, user menu with roles and sign-out, flash and htmx toast. Area URL
 * rules answer 403 to roles outside the area.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ShellTestPages.class)
class AppLayoutTest {

	private final MockMvc mvc;

	@Autowired
	AppLayoutTest(MockMvc mvc) {
		this.mvc = mvc;
	}

	@Test
	@WithMockUser(username = "meera.deshpande@kosh.test", roles = { "HR", "EMPLOYEE" })
	void hrUserGetsSidebarTopBarAndUserMenu() throws Exception {
		String page = render(get("/hr/employees"), "test-only/app-page");

		assertThat(page).contains("<title>Employees | Kosh</title>")
			.contains("<a class=\"skip-link\" href=\"#main\">Skip to content</a>")
			.contains("<main class=\"page-content\" id=\"main\" tabindex=\"-1\">")
			.contains("<p>Directory content</p>")
			.doesNotContain("th:");
		// Sidebar: a popover (drawer below 960px) with the groups and pages this user may open.
		assertThat(page).contains("<aside class=\"sidebar\" id=\"app-sidebar\" popover>")
			.contains("<nav class=\"sidebar-nav\" aria-label=\"Main\">")
			.contains(">Overview</p>")
			.contains(">People</p>")
			.contains(">Payroll</p>")
			.doesNotContain(">Settings</p>")
			.contains("<a class=\"nav-link\" href=\"/hr/employees\" title=\"Employees\" aria-current=\"page\">")
			.contains("<a class=\"nav-link\" href=\"/hr\" title=\"HR dashboard\">")
			.contains("<a class=\"nav-link\" href=\"/me/payslips\" title=\"My payslips\">")
			.doesNotContain("href=\"/payroll/runs\"")
			.doesNotContain("href=\"/admin/users\"");
		assertThat(page.split("aria-current=\"page\"", -1)).as("one active item plus the breadcrumb").hasSize(3);
		// Top bar: drawer button, breadcrumbs, title, employee search, bell, user menu.
		assertThat(page).containsPattern("popovertarget=\"app-sidebar\"\\s+aria-label=\"Open menu\"")
			.contains("<nav class=\"breadcrumbs\" aria-label=\"Breadcrumb\">")
			.contains("<li><a href=\"/hr\">HR</a></li>")
			.contains("<li aria-current=\"page\">Employees</li>")
			.contains("<h1 class=\"page-title\">Employees</h1>")
			.containsPattern("role=\"search\" method=\"get\"\\s+action=\"/hr/employees\"")
			.contains("<label class=\"visually-hidden\" for=\"employee-search\">Search employees</label>")
			.contains("<input class=\"input\" id=\"employee-search\" name=\"q\" type=\"search\"")
			.contains("popovertarget=\"notifications-menu\" aria-label=\"Notifications\"")
			.containsPattern("id=\"notification-count\" aria-hidden=\"true\"\\s+hidden=\"hidden\">0</span>")
			.contains("You’re all caught up")
			.contains("<div class=\"menu-panel\" id=\"notifications-menu\" popover role=\"dialog\" aria-labelledby=\"notifications-title\">")
			.contains("<a class=\"menu-footer-link\" href=\"/notifications\">View all notifications</a>");
		assertThat(page).contains("aria-label=\"Account menu for meera.deshpande@kosh.test\"")
			.contains("<div class=\"menu-panel\" id=\"user-menu\" popover role=\"dialog\" aria-label=\"Account\">")
			.contains("<span class=\"avatar\" aria-hidden=\"true\">MD</span>")
			.contains("<li class=\"badge badge-info\">HR</li>")
			.contains("<li class=\"badge badge-info\">Employee</li>")
			.doesNotContain("<li class=\"badge badge-info\">Admin</li>")
			.contains("<a class=\"menu-item\" href=\"/me/profile\">")
			.containsPattern("<form action=\"/logout\" method=\"post\"><input type=\"hidden\" name=\"_csrf\" value=\"[^\"]+\"/>")
			.contains("Sign out</button>");
		// htmx on every page, with the CSRF token for its requests, and the toast region for its responses.
		assertThat(page).containsPattern("<script src=\"/webjars/htmx\\.org/[^\"]+/htmx\\.min\\.js\" defer></script>")
			.contains("<script src=\"/js/htmx-config.js\" defer></script>")
			.contains("<script src=\"/js/commands.js\" defer></script>")
			.containsPattern("<meta name=\"_csrf\" content=\"[^\"]+\">")
			.contains("<meta name=\"_csrf_header\" content=\"X-CSRF-TOKEN\">")
			.contains("<div class=\"toast-region\" id=\"toast-region\" aria-live=\"polite\"></div>");
	}

	@Test
	@WithMockUser(username = "admin@kosh.test", roles = "ADMIN")
	void adminSeesSettingsAndEveryOfficeAreaButNoSelfService() throws Exception {
		String page = render(get("/admin/users"), "test-only/app-page");

		assertThat(page).contains(">Settings</p>")
			.contains("<a class=\"nav-link\" href=\"/admin/users\" title=\"Users and roles\" aria-current=\"page\">")
			.contains("href=\"/hr/employees\"")
			.contains("href=\"/payroll/runs\"")
			.contains("<li class=\"badge badge-info\">Admin</li>")
			.doesNotContain("href=\"/me/profile\"")
			.doesNotContain("href=\"/me/payslips\"");
	}

	@Test
	@WithMockUser(username = "payroll@kosh.test", roles = { "PAYROLL", "EMPLOYEE" })
	void payrollUserSeesPayrollPagesWithoutTheEmployeeSearch() throws Exception {
		String page = render(get("/payroll/runs"), "test-only/app-page");

		assertThat(page).contains("<a class=\"nav-link\" href=\"/payroll/runs\" title=\"Payroll runs\" aria-current=\"page\">")
			.contains("href=\"/me/payslips\"")
			.doesNotContain("href=\"/hr/employees\"")
			.doesNotContain("class=\"nav-link\" href=\"/hr")
			.doesNotContain("role=\"search\"");
	}

	@Test
	@WithMockUser(username = "employee@kosh.test", roles = "EMPLOYEE")
	void employeeSeesOnlySelfService() throws Exception {
		String page = render(get("/me/payslips"), "test-only/app-page");

		assertThat(page).contains("<a class=\"nav-link\" href=\"/me/payslips\" title=\"My payslips\" aria-current=\"page\">")
			.contains("href=\"/me/profile\"")
			.contains("href=\"/notifications\"")
			.doesNotContain("class=\"nav-link\" href=\"/hr")
			.doesNotContain("class=\"nav-link\" href=\"/payroll")
			.doesNotContain("class=\"nav-link\" href=\"/admin")
			.doesNotContain("role=\"search\"")
			.contains("<li class=\"badge badge-info\">Employee</li>");
	}

	@Test
	@WithMockUser(username = "employee@kosh.test", roles = "EMPLOYEE")
	void employeeIsForbiddenInOfficeAreas() throws Exception {
		// The area root itself is inside the area, not only the paths below it.
		mvc.perform(get("/hr")).andExpect(status().isForbidden());
		mvc.perform(get("/hr/employees")).andExpect(status().isForbidden());
		mvc.perform(get("/payroll/runs")).andExpect(status().isForbidden());
		mvc.perform(get("/admin/users")).andExpect(status().isForbidden());
	}

	@Test
	@WithMockUser(username = "hr@kosh.test", roles = { "HR", "EMPLOYEE" })
	void hrIsForbiddenInPayrollAndAdmin() throws Exception {
		mvc.perform(get("/payroll/runs")).andExpect(status().isForbidden());
		mvc.perform(get("/admin/users")).andExpect(status().isForbidden());
	}

	@Test
	@WithMockUser(username = "manager@kosh.test", roles = { "MANAGER", "EMPLOYEE" })
	void managerNeverReachesSalaryAreas() throws Exception {
		mvc.perform(get("/hr/employees")).andExpect(status().isForbidden());
		mvc.perform(get("/payroll/runs")).andExpect(status().isForbidden());
		render(get("/me/payslips"), "test-only/app-page");
	}

	@Test
	@WithMockUser(username = "nisha.kale@kosh.test", roles = { "HR", "PAYROLL", "EMPLOYEE" })
	void multiRoleUserReachesEachOfItsAreasAndSeesThemAll() throws Exception {
		render(get("/hr/employees"), "test-only/app-page");
		render(get("/me/payslips"), "test-only/app-page");
		String page = render(get("/payroll/runs"), "test-only/app-page");

		assertThat(page).contains("href=\"/hr/employees\"")
			.contains("href=\"/payroll/runs\" title=\"Payroll runs\" aria-current=\"page\"")
			.contains("<li class=\"badge badge-info\">HR</li>")
			.contains("<li class=\"badge badge-info\">Payroll</li>")
			.contains("<span class=\"avatar\" aria-hidden=\"true\">NK</span>");
		mvc.perform(get("/admin/users")).andExpect(status().isForbidden());
	}

	@Test
	@WithMockUser(username = "payroll@kosh.test", roles = { "PAYROLL", "EMPLOYEE" })
	void flashMessageAfterARedirectIsShownAboveThePage() throws Exception {
		String success = render(get("/payroll/runs").flashAttr(Flash.ATTRIBUTE,
				Flash.success("September payroll is ready for approval").withText("Calculated for 148 employees.")),
				"test-only/app-page");
		assertThat(success).contains("<div class=\"flash flash-success\" role=\"status\">")
			.contains("<use href=\"/img/icons.svg#i-check\"></use>")
			.contains("<p class=\"flash-title\">September payroll is ready for approval</p>")
			.contains("<p class=\"flash-text\">Calculated for 148 employees.</p>");

		String danger = render(get("/payroll/runs").flashAttr(Flash.ATTRIBUTE,
				Flash.danger("3 employees have no bank details")), "test-only/app-page");
		assertThat(danger).contains("<div class=\"flash flash-danger\" role=\"alert\">")
			.contains("<p class=\"flash-title\">3 employees have no bank details</p>")
			.doesNotContain("class=\"flash-text\"");

		assertThat(render(get("/payroll/runs"), "test-only/app-page")).doesNotContain("class=\"flash ");
	}

	@Test
	@WithMockUser(username = "payroll@kosh.test", roles = { "PAYROLL", "EMPLOYEE" })
	void htmxResponsesAppendAToastOutOfBand() throws Exception {
		String fragment = mvc.perform(get("/test-only/toast").header("HX-Request", "true"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(fragment).startsWith("<div hx-swap-oob=\"beforeend:#toast-region\">")
			.contains("<div class=\"toast toast-success\">")
			.contains("<p class=\"flash-title\">Draft saved</p>")
			.contains("<p class=\"flash-text\">Salary structure for KSH-0003.</p>")
			.doesNotContain("<html");
	}

	@Test
	@WithMockUser(username = "hr@kosh.test", roles = { "HR", "EMPLOYEE" })
	void signedInVisitorsGetSignOutInsteadOfSignInOnPublicPages() throws Exception {
		String page = render(get("/"), "public/home");

		assertThat(page).containsPattern("<form class=\"site-signout\" action=\"/logout\" method=\"post\">"
				+ "<input type=\"hidden\" name=\"_csrf\" value=\"[^\"]+\"/>")
			.contains("<a class=\"btn btn-primary btn-sm\" href=\"/home\">Open Kosh</a>")
			.contains("<meta name=\"_csrf_header\" content=\"X-CSRF-TOKEN\">")
			.doesNotContain("href=\"/login\"");
	}

	@Test
	@WithMockUser(username = "hr@kosh.test", roles = { "HR", "EMPLOYEE" })
	void appPagesCarryTheSecurityHeaders() throws Exception {
		mvc.perform(get("/hr/employees"))
			.andExpect(header().string("Content-Security-Policy", SecurityConfig.CONTENT_SECURITY_POLICY))
			.andExpect(header().string("X-Content-Type-Options", "nosniff"))
			.andExpect(header().string("X-Frame-Options", "DENY"));
	}

	@Test
	@WithMockUser(username = "hr@kosh.test", roles = { "HR", "EMPLOYEE" })
	void formFieldsShowLabelHelpAndInlineErrors() throws Exception {
		String page = fragmentsPage();

		assertThat(page).contains("<label class=\"label\" for=\"email\">")
			.contains("<input class=\"input\" type=\"email\" id=\"email\" autocomplete=\"email\" "
					+ "aria-describedby=\"email-help email-error\" aria-invalid=\"true\" name=\"email\" value=\"not-an-email\">")
			.contains("<p class=\"help\" id=\"email-help\">Payslips go to this address.</p>")
			.contains("<p class=\"error-text\" id=\"email-error\">")
			.contains("<span>Enter a work email like name@company.in.</span>")
			// Select keeps the submitted value; no help, no error, no aria attributes.
			.contains("<select class=\"select\" id=\"department\" name=\"department\">")
			.contains("<option value=\"\">Select a department</option>")
			.contains("<option value=\"QA\" selected=\"selected\">Quality</option>")
			.contains("<option value=\"HRA\">HR &amp; Admin</option>")
			// Optional fields say so in the label.
			.contains("<span class=\"optional\">(optional)</span>")
			.contains("<textarea class=\"textarea\" id=\"note\" rows=\"3\" name=\"note\"></textarea>")
			.containsPattern("<form class=\"card card-body form-grid\" method=\"post\" action=\"/test-only/fragments\">"
					+ "<input type=\"hidden\" name=\"_csrf\" value=\"[^\"]+\"/>");
	}

	@Test
	@WithMockUser(username = "hr@kosh.test", roles = { "HR", "EMPLOYEE" })
	void sortHeadersAndPaginationKeepTheSearch() throws Exception {
		String page = fragmentsPage();

		// The sorted column says so and links to the other direction; paging restarts.
		assertThat(page).contains("<th scope=\"col\" aria-sort=\"ascending\">")
			.contains("<a class=\"sort\" href=\"/test-only/fragments?q=patil&amp;sort=name,desc\">")
			.contains("<th scope=\"col\" class=\"num\">")
			.contains("<a class=\"sort\" href=\"/test-only/fragments?q=patil&amp;sort=net,asc\">");
		// Page 3 of 20: first, neighbours, a gap, last; links keep search and sort.
		assertThat(page).contains("<nav class=\"pagination\" aria-label=\"Employee pages\">")
			.contains("Showing <strong>51–75</strong>")
			.contains("of <strong>500</strong> <span>employees</span>")
			.contains("<a class=\"page-link page-prev\" href=\"/test-only/fragments?q=patil&amp;sort=name,asc&amp;page=1\" rel=\"prev\">")
			.contains("href=\"/test-only/fragments?q=patil&amp;sort=name,asc&amp;page=2\" aria-current=\"page\" aria-label=\"Page 3\">3</a>")
			.contains("aria-label=\"Page 1\">1</a>")
			.contains("aria-label=\"Page 4\">4</a>")
			.contains("<span class=\"page-link\" aria-hidden=\"true\">…</span>")
			.contains("href=\"/test-only/fragments?q=patil&amp;sort=name,asc&amp;page=19\" aria-label=\"Page 20\">20</a>")
			.contains("<a class=\"page-link\" href=\"/test-only/fragments?q=patil&amp;sort=name,asc&amp;page=3\" rel=\"next\">")
			.doesNotContain("aria-label=\"Page 6\"");
	}

	@Test
	@WithMockUser(username = "hr@kosh.test", roles = { "HR", "EMPLOYEE" })
	void paginationDisablesPreviousOnTheFirstPage() throws Exception {
		String page = render(get("/test-only/fragments"), "test-only/fragments-page");

		assertThat(page).contains("<span class=\"page-link page-prev\" aria-disabled=\"true\">")
			.contains("Showing <strong>1–25</strong>")
			.contains("aria-current=\"page\" aria-label=\"Page 1\">1</a>")
			.contains("<th scope=\"col\">")
			.contains("<a class=\"sort\" href=\"/test-only/fragments?sort=name,asc\">");
	}

	@Test
	@WithMockUser(username = "hr@kosh.test", roles = { "HR", "EMPLOYEE" })
	void displayFragmentsRenderMoneyBadgesCardsEmptyStatesStepsTimelineAndDetails() throws Exception {
		String page = fragmentsPage();

		assertThat(page).contains("<td class=\"num\">₹81,638.00</td>")
			.contains("<span class=\"num neg\">-₹1,250.00</span>")
			.contains("<span class=\"badge\" data-status=\"ON_NOTICE\">On notice</span>")
			.contains("<span class=\"badge\" data-status=\"PAID\">Paid on 30 Sep 2026</span>")
			.contains("<div class=\"card stat\">")
			.contains("<span>Employees paid</span>")
			.contains("<p class=\"stat-value\">148</p>")
			.contains("<p class=\"stat-meta\">3 joiners this month</p>");
		// Empty states, with and without an action.
		assertThat(page).contains("<img src=\"/img/empty/search.svg\" alt=\"\" width=\"160\" height=\"120\">")
			.contains("<h2 class=\"empty-title\">No employees match these filters</h2>")
			.contains("<div class=\"cluster\"><a class=\"btn btn-secondary\" href=\"/test-only/fragments\">Clear filters</a></div>")
			.contains("<h2 class=\"empty-title\">You’re all caught up</h2>");
		// Stepper on step 3 of 5: two complete, one current.
		assertThat(page).contains("<ol class=\"stepper\" aria-label=\"Add employee\">")
			.contains("<li class=\"step\" aria-current=\"step\">")
			.contains("<span class=\"step-label\"><span>Statutory</span></span>");
		assertThat(page.split("<li class=\"step is-complete\">", -1)).hasSize(3);
		assertThat(page.split("<span class=\"visually-hidden\">Completed: </span>", -1)).hasSize(3);
		// Timeline and description list.
		assertThat(page).contains("<li class=\"timeline-item is-danger\">")
			.contains("<p class=\"timeline-body\">Overtime for the Shiroli second shift is missing.</p>")
			.contains("<p class=\"timeline-meta\">Priya Joshi, Payroll · 25 Sep 2026, 10:12 AM</p>")
			.contains("<dt>Employee code</dt>")
			.contains("<dd>KSH-0003</dd>")
			.containsPattern("<dt>UAN</dt>\\s*<dd>—</dd>");
	}

	private String fragmentsPage() throws Exception {
		return render(get("/test-only/fragments?q=patil&sort=name,asc&page=2"), "test-only/fragments-page");
	}

	private String render(MockHttpServletRequestBuilder request, String viewName) throws Exception {
		return mvc.perform(request)
			.andExpect(status().isOk())
			.andExpect(view().name(viewName))
			.andReturn()
			.getResponse()
			.getContentAsString();
	}

}
