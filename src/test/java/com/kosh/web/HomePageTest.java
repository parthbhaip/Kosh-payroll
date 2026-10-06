package com.kosh.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.Locale;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.kosh.security.KoshUserDetails;
import com.kosh.user.Role;
import com.kosh.user.User;

/** The workspace homes where sign-in lands, rendered for real principals at the test clock (Mon 5 Oct 2026, 10:00). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HomePageTest {

	private final MockMvc mvc;

	@Autowired
	HomePageTest(MockMvc mvc) {
		this.mvc = mvc;
	}

	@Test
	void hrHomeGreetsTheUserAndOffersTheirOtherWorkspaces() throws Exception {
		String page = render("/hr", signedIn("Meera Deshpande", Role.HR, Role.PAYROLL, Role.EMPLOYEE));

		assertThat(page).contains("<title>HR dashboard | Kosh</title>")
			.contains("<h1 class=\"page-title\">HR dashboard</h1>")
			.doesNotContain("class=\"breadcrumbs\"")
			.contains("<p class=\"eyebrow\">Monday, 5 October 2026</p>")
			.contains("<h2 class=\"welcome-title\" id=\"welcome-title\">Good morning, Meera</h2>")
			.contains("Employees, organisation, salary structures, leave and attendance policies, exits and declaration checks.")
			.contains("<dd>meera.deshpande@home.kosh.test</dd>")
			.contains("<li class=\"badge badge-info\">HR</li>")
			.contains("<li class=\"badge badge-info\">Payroll</li>");
		// Workspaces: the current one marked, the others linked, none the roles do not open.
		assertThat(page).contains("<h2 class=\"section-title\" id=\"workspaces-title\">Your workspaces</h2>")
			.containsPattern("<a class=\"card workspace-card\" href=\"/hr\"\\s+aria-current=\"page\">")
			.contains("<span class=\"workspace-here\">You are here</span>")
			.containsPattern("<a class=\"card workspace-card\" href=\"/payroll\"\\s*>")
			.containsPattern("<a class=\"card workspace-card\" href=\"/me\"\\s*>")
			.doesNotContain("workspace-card\" href=\"/admin\"")
			.doesNotContain("workspace-card\" href=\"/finance\"");
		// The shell: home pages in the sidebar, the person's name in the user menu, the logo back to their home.
		assertThat(page).contains("<a class=\"nav-link\" href=\"/hr\" title=\"HR dashboard\" aria-current=\"page\">")
			.contains("<a class=\"nav-link\" href=\"/payroll\" title=\"Payroll dashboard\">")
			.contains("<a class=\"nav-link\" href=\"/me\" title=\"My home\">")
			.doesNotContain("href=\"/admin\" title=\"Admin dashboard\"")
			.contains("aria-label=\"Account menu for Meera Deshpande\"")
			.contains("<span class=\"avatar\" aria-hidden=\"true\">MD</span>")
			.contains("<a class=\"sidebar-brand\" href=\"/home\">")
			.contains("<a class=\"btn btn-secondary btn-sm\" href=\"/help\">Open help</a>");
	}

	@Test
	void anEmployeeHasOneWorkspaceSoNoSwitcher() throws Exception {
		String page = render("/me", signedIn("Sandeep", Role.EMPLOYEE));

		assertThat(page).contains("<title>My home | Kosh</title>")
			.contains("Good morning, Sandeep</h2>")
			.contains("Your payslips, tax regime and declarations, leave, attendance and reimbursement claims.")
			.doesNotContain("Your workspaces")
			.doesNotContain("workspace-card");
	}

	@Test
	void adminSeesEveryOfficeWorkspaceButNoSelfService() throws Exception {
		String page = render("/admin", signedIn("Kavita Joshi", Role.ADMIN));

		assertThat(page).contains("<title>Admin dashboard | Kosh</title>")
			.containsPattern("workspace-card\" href=\"/admin\"\\s+aria-current=\"page\"")
			.contains("workspace-card\" href=\"/hr\"")
			.contains("workspace-card\" href=\"/payroll\"")
			.contains("workspace-card\" href=\"/finance\"")
			.doesNotContain("workspace-card\" href=\"/me\"");
	}

	@Test
	void payrollAndFinanceHomesNameTheirWork() throws Exception {
		assertThat(render("/payroll", signedIn("Priya Joshi", Role.PAYROLL, Role.EMPLOYEE)))
			.contains("<title>Payroll dashboard | Kosh</title>")
			.contains("Monthly payroll inputs and runs, payslips and statutory compliance files.");
		assertThat(render("/finance", signedIn("Nikhil Pawar", Role.FINANCE, Role.EMPLOYEE)))
			.contains("<title>Finance dashboard | Kosh</title>")
			.contains("Payroll approvals, bank transfer files, payments and finance reports.");
	}

	@Test
	void principalsWithoutADisplayNameAreGreetedWithoutOne() throws Exception {
		assertThat(render("/me", user("employee@kosh.test").roles("EMPLOYEE"))).contains("Good morning</h2>")
			.contains("<dd>employee@kosh.test</dd>");
	}

	@ParameterizedTest
	@CsvSource({ "0, Good morning", "11, Good morning", "12, Good afternoon", "16, Good afternoon", "17, Good evening",
			"23, Good evening" })
	void greetingFollowsTheTimeOfDay(int hour, String greeting) {
		assertThat(HomeController.greeting(hour)).isEqualTo(greeting);
	}

	@Test
	void firstNameIsTheFirstWord() {
		assertThat(HomeController.firstName("  Meera   Deshpande ")).isEqualTo("Meera");
		assertThat(HomeController.firstName("Sandeep")).isEqualTo("Sandeep");
	}

	private String render(String path, RequestPostProcessor principal) throws Exception {
		return mvc.perform(get(path).with(principal))
			.andExpect(status().isOk())
			.andExpect(view().name("home/index"))
			.andReturn()
			.getResponse()
			.getContentAsString();
	}

	private static RequestPostProcessor signedIn(String displayName, Role... roles) {
		String local = displayName.toLowerCase(Locale.ROOT).replace(' ', '.');
		User user = new User(local + "@home.kosh.test", displayName, Set.of(roles));
		user.setPasswordHash("{bcrypt}not-used-here");
		return user(new KoshUserDetails(user, false));
	}

}
