package com.kosh.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;

import com.kosh.user.Role;
import com.kosh.web.AppShell.NavGroup;
import com.kosh.web.AppShell.NavItem;
import com.kosh.web.AppShell.Shell;

/** The sidebar, search, bell and user menu the app layout shows, by role and by which pages exist. */
class AppShellTest {

	/** Pages that "exist" in these tests; every other menu path is treated as not built yet. */
	private static final Set<String> ROUTES = Set.of("/admin", "/admin/users", "/hr", "/hr/employees", "/payroll",
			"/payroll/runs", "/finance", "/finance/approvals", "/team", "/me", "/me/profile", "/me/payslips",
			"/notifications");

	@Test
	void hrUserSeesPeoplePagesAndOwnSelfServiceButNoPayrollFinanceOrSettings() {
		Shell shell = AppShell.build(user("meera.deshpande@kosh.test", Role.HR, Role.EMPLOYEE), "/hr/employees",
				ROUTES::contains);

		assertThat(titles(shell)).containsExactly("Overview", "People", "Payroll");
		assertThat(hrefs(shell)).containsExactly("/hr", "/me", "/notifications", "/hr/employees", "/me/profile",
				"/me/payslips");
		assertThat(active(shell)).containsExactly("/hr/employees");
		assertThat(shell.searchAction()).isEqualTo("/hr/employees");
		assertThat(shell.profileHref()).isEqualTo("/me/profile");
		assertThat(shell.notificationsHref()).isEqualTo("/notifications");
		assertThat(shell.user().name()).isEqualTo("meera.deshpande@kosh.test");
		assertThat(shell.user().initials()).isEqualTo("MD");
		assertThat(shell.user().roles()).containsExactly("HR", "Employee");
	}

	@Test
	void adminReachesTheHrPayrollAndFinanceAreasButNotTeamOrSelfService() {
		Shell shell = AppShell.build(user("admin@kosh.test", Role.ADMIN), "/admin/users", ROUTES::contains);

		assertThat(titles(shell)).containsExactly("Overview", "People", "Payroll", "Settings");
		assertThat(hrefs(shell)).containsExactly("/admin", "/hr", "/payroll", "/finance", "/notifications",
				"/hr/employees", "/payroll/runs", "/finance/approvals", "/admin/users");
		assertThat(active(shell)).containsExactly("/admin/users");
		assertThat(shell.searchAction()).isEqualTo("/hr/employees");
		// No EMPLOYEE role, so no self-service profile link.
		assertThat(shell.profileHref()).isNull();
	}

	@Test
	void payrollUserSeesPayrollPagesWithoutTheEmployeeSearch() {
		Shell shell = AppShell.build(user("payroll@kosh.test", Role.PAYROLL, Role.EMPLOYEE), "/payroll/runs/7",
				ROUTES::contains);

		assertThat(hrefs(shell)).contains("/payroll", "/payroll/runs", "/me/payslips")
			.doesNotContain("/hr", "/hr/employees", "/finance/approvals", "/admin/users");
		assertThat(active(shell)).containsExactly("/payroll/runs");
		assertThat(shell.searchAction()).isNull();
	}

	@Test
	void managerSeesTheTeamButNoSalaryPagesAndNoEmployeeSearch() {
		Shell shell = AppShell.build(user("manager@kosh.test", Role.MANAGER, Role.EMPLOYEE), "/team", ROUTES::contains);

		assertThat(hrefs(shell)).containsExactly("/me", "/notifications", "/team", "/me/profile", "/me/payslips");
		assertThat(active(shell)).containsExactly("/team");
		assertThat(shell.searchAction()).isNull();
	}

	@Test
	void pagesThatAreNotBuiltYetNeverAppear() {
		Shell shell = AppShell.build(user("admin@kosh.test", Role.values()), "/notifications",
				"/notifications"::equals);

		assertThat(titles(shell)).containsExactly("Overview");
		assertThat(hrefs(shell)).containsExactly("/notifications");
		assertThat(shell.searchAction()).isNull();
		assertThat(shell.profileHref()).isNull();
		assertThat(shell.unreadNotifications()).isZero();
	}

	@Test
	void theLongestMatchingMenuPathIsActive() {
		Authentication hr = user("hr@kosh.test", Role.HR, Role.EMPLOYEE);
		assertThat(active(AppShell.build(hr, "/hr/employees/42/edit", ROUTES::contains))).containsExactly("/hr/employees");
		assertThat(active(AppShell.build(hr, "/hr", ROUTES::contains))).containsExactly("/hr");
		assertThat(active(AppShell.build(hr, "/hrx", ROUTES::contains))).isEmpty();
		assertThat(active(AppShell.build(hr, "/me/payslips/2026-09", ROUTES::contains))).containsExactly("/me/payslips");
	}

	@Test
	void authoritiesThatAreNotKoshRolesAreIgnored() {
		Authentication other = UsernamePasswordAuthenticationToken.authenticated("hr@kosh.test", null,
				AuthorityUtils.createAuthorityList("ROLE_USER", "SCOPE_read", "ROLE_HR"));
		Shell shell = AppShell.build(other, "/hr", ROUTES::contains);

		assertThat(shell.user().roles()).containsExactly("HR");
		assertThat(hrefs(shell)).contains("/hr").doesNotContain("/me", "/admin");
	}

	@Test
	void initialsComeFromTheNameOrTheEmailAddress() {
		assertThat(AppShell.initials("Meera Deshpande")).isEqualTo("MD");
		assertThat(AppShell.initials("priya.joshi@kosh.test")).isEqualTo("PJ");
		assertThat(AppShell.initials("hr@kosh.test")).isEqualTo("H");
		assertThat(AppShell.initials("sandeep anil patil")).isEqualTo("SA");
		assertThat(AppShell.initials("")).isEmpty();
	}

	@Test
	void everyMenuPathIsUnique() {
		List<String> paths = AppShell.MENU.stream().map(AppShell.Entry::path).toList();
		assertThat(paths).doesNotHaveDuplicates();
	}

	private static Authentication user(String email, Role... roles) {
		return UsernamePasswordAuthenticationToken.authenticated(email, null,
				AuthorityUtils.createAuthorityList(Arrays.stream(roles).map(Role::authority).toList()));
	}

	private static List<String> titles(Shell shell) {
		return shell.nav().stream().map(NavGroup::title).toList();
	}

	private static List<String> hrefs(Shell shell) {
		return shell.nav().stream().flatMap(group -> group.items().stream()).map(NavItem::href).toList();
	}

	private static List<String> active(Shell shell) {
		return shell.nav()
			.stream()
			.flatMap(group -> group.items().stream())
			.filter(NavItem::active)
			.map(NavItem::href)
			.toList();
	}

}
