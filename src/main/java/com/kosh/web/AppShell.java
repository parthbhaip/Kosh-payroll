package com.kosh.web;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.function.SingletonSupplier;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.kosh.security.Area;
import com.kosh.user.Role;

/**
 * What the app layout shows around a page for the signed-in user: the sidebar, the employee search, the
 * notifications bell and the user menu. Templates read it as {@code ${@appShell.current()}}.
 * <p>
 * {@link #MENU} lists every planned page. An item appears only when the user's roles may open its {@link Area} and a
 * GET handler for its exact path exists, so the sidebar never links to a page that is not built yet. A task that builds
 * one of these pages must use the path given here (or change it here).
 */
@Component("appShell")
public class AppShell {

	/** Sidebar groups, in display order (CLAUDE.md > UI). */
	enum Section {

		OVERVIEW("Overview"), PEOPLE("People"), TIME("Time"), PAYROLL("Payroll"), COMPLIANCE("Compliance"),
		REPORTS("Reports"), SETTINGS("Settings");

		private final String title;

		Section(String title) {
			this.title = title;
		}

	}

	record Entry(Section section, String label, String icon, String path) {

	}

	static final List<Entry> MENU = List.of(
			new Entry(Section.OVERVIEW, "Admin dashboard", "dashboard", "/admin"),
			new Entry(Section.OVERVIEW, "HR dashboard", "dashboard", "/hr"),
			new Entry(Section.OVERVIEW, "Payroll dashboard", "dashboard", "/payroll"),
			new Entry(Section.OVERVIEW, "Finance dashboard", "dashboard", "/finance"),
			new Entry(Section.OVERVIEW, "My home", "dashboard", "/me"),
			new Entry(Section.OVERVIEW, "Notifications", "bell", "/notifications"),
			new Entry(Section.PEOPLE, "Employees", "users", "/hr/employees"),
			new Entry(Section.PEOPLE, "Organisation", "building", "/hr/org"),
			new Entry(Section.PEOPLE, "Exits", "briefcase", "/hr/exits"),
			new Entry(Section.PEOPLE, "My team", "users", "/team"),
			new Entry(Section.PEOPLE, "My profile", "user", "/me/profile"),
			new Entry(Section.TIME, "Leave", "calendar", "/hr/leave"),
			new Entry(Section.TIME, "Attendance", "clock", "/hr/attendance"),
			new Entry(Section.TIME, "Holidays", "calendar", "/hr/holidays"),
			new Entry(Section.TIME, "Team leave", "calendar", "/team/leave"),
			new Entry(Section.TIME, "My leave", "calendar", "/me/leave"),
			new Entry(Section.TIME, "My attendance", "clock", "/me/attendance"),
			new Entry(Section.PAYROLL, "Payroll runs", "wallet", "/payroll/runs"),
			new Entry(Section.PAYROLL, "Payroll approvals", "check", "/finance/approvals"),
			new Entry(Section.PAYROLL, "Salary components", "rupee", "/hr/salary/components"),
			new Entry(Section.PAYROLL, "Salary templates", "file-text", "/hr/salary/templates"),
			new Entry(Section.PAYROLL, "Loans and advances", "bank", "/payroll/loans"),
			new Entry(Section.PAYROLL, "Tax declarations", "file-text", "/hr/declarations"),
			new Entry(Section.PAYROLL, "Reimbursements", "receipt", "/finance/reimbursements"),
			new Entry(Section.PAYROLL, "My payslips", "receipt", "/me/payslips"),
			new Entry(Section.PAYROLL, "My tax", "file-text", "/me/tax"),
			new Entry(Section.PAYROLL, "My claims", "receipt", "/me/reimbursements"),
			new Entry(Section.COMPLIANCE, "Statutory filings", "shield", "/payroll/compliance"),
			new Entry(Section.COMPLIANCE, "Bank files", "bank", "/finance/bank-files"),
			new Entry(Section.REPORTS, "Payroll reports", "chart", "/payroll/reports"),
			new Entry(Section.REPORTS, "Finance reports", "chart", "/finance/reports"),
			new Entry(Section.SETTINGS, "Company", "building", "/admin/company"),
			new Entry(Section.SETTINGS, "Users and roles", "lock", "/admin/users"),
			new Entry(Section.SETTINGS, "Statutory rules", "shield", "/admin/statutory"),
			new Entry(Section.SETTINGS, "Audit log", "file", "/admin/audit"));

	/** Where the top-bar employee search sends its {@code q} parameter. */
	static final String EMPLOYEE_SEARCH = "/hr/employees";

	static final String NOTIFICATIONS = "/notifications";

	static final String PROFILE = "/me/profile";

	public record Shell(List<NavGroup> nav, CurrentUser user, String searchAction, String notificationsHref,
			String profileHref, int unreadNotifications) {

	}

	public record NavGroup(String title, List<NavItem> items) {

	}

	public record NavItem(String label, String icon, String href, boolean active) {

	}

	public record CurrentUser(String name, String initials, List<String> roles) {

	}

	private final Supplier<Set<String>> routes;

	AppShell(@Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mapping) {
		// Handler methods are fixed once the context has started; read them on first use.
		this.routes = SingletonSupplier.of(() -> getRoutes(mapping));
	}

	/** The shell for the request being rendered. */
	public Shell current() {
		HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes())
			.getRequest();
		String path = request.getRequestURI().substring(request.getContextPath().length());
		return build(SecurityContextHolder.getContext().getAuthentication(), path, routes.get()::contains);
	}

	static Shell build(Authentication authentication, String path, Predicate<String> routeExists) {
		Set<Role> roles = roles(authentication);
		Predicate<String> available = href -> routeExists.test(href)
				&& Area.of(href).map(area -> area.allows(roles)).orElse(true);
		String active = MENU.stream()
			.map(Entry::path)
			.filter(available)
			.filter(href -> path.equals(href) || path.startsWith(href + "/"))
			.max(Comparator.comparingInt(String::length))
			.orElse(null);

		List<NavGroup> nav = new ArrayList<>();
		for (Section section : Section.values()) {
			List<NavItem> items = MENU.stream()
				.filter(entry -> entry.section() == section && available.test(entry.path()))
				.map(entry -> new NavItem(entry.label(), entry.icon(), entry.path(), entry.path().equals(active)))
				.toList();
			if (!items.isEmpty()) {
				nav.add(new NavGroup(section.title, items));
			}
		}
		String name = (authentication != null) ? authentication.getName() : "";
		CurrentUser user = new CurrentUser(name, initials(name), roles.stream().map(Role::label).toList());
		// Notifications arrive with T51; until then the bell shows no count.
		return new Shell(nav, user, orNull(EMPLOYEE_SEARCH, available), orNull(NOTIFICATIONS, available),
				orNull(PROFILE, available), 0);
	}

	private static Set<Role> roles(Authentication authentication) {
		Set<Role> roles = EnumSet.noneOf(Role.class);
		if (authentication != null && authentication.isAuthenticated()) {
			for (GrantedAuthority authority : authentication.getAuthorities()) {
				Role.fromAuthority(authority.getAuthority()).ifPresent(roles::add);
			}
		}
		return roles;
	}

	/** {@code MD} for "Meera Deshpande", {@code PJ} for "priya.joshi@kosh.test", {@code H} for "hr@kosh.test". */
	static String initials(String name) {
		String local = name.contains("@") ? name.substring(0, name.indexOf('@')) : name;
		return Arrays.stream(local.split("[^\\p{L}\\p{N}]+"))
			.filter(part -> !part.isEmpty())
			.limit(2)
			.map(part -> part.substring(0, 1).toUpperCase(Locale.ROOT))
			.collect(Collectors.joining());
	}

	private static String orNull(String href, Predicate<String> available) {
		return available.test(href) ? href : null;
	}

	private static Set<String> getRoutes(RequestMappingHandlerMapping mapping) {
		return mapping.getHandlerMethods()
			.keySet()
			.stream()
			.filter(AppShell::answersGet)
			.flatMap(info -> info.getPatternValues().stream())
			.collect(Collectors.toUnmodifiableSet());
	}

	private static boolean answersGet(RequestMappingInfo info) {
		Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
		return methods.isEmpty() || methods.contains(RequestMethod.GET);
	}

}
