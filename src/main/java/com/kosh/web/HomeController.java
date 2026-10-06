package com.kosh.web;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import com.kosh.security.Area;
import com.kosh.security.KoshUserDetails;
import com.kosh.user.Role;

/**
 * The home page of each workspace, where sign-in lands: who is signed in, what the workspace is for, and the other
 * workspaces the user's roles open. The URL rules already limit each path to its area's roles.
 */
@Controller
class HomeController {

	/** The workspaces with a home page, in the order of {@link com.kosh.security.Landing}. */
	enum Workspace {

		ADMIN(Area.ADMIN, "Admin dashboard", "Admin", "settings",
				"Company settings, users and roles, statutory rule versions and the audit log."),
		HR(Area.HR, "HR dashboard", "HR", "users",
				"Employees, organisation, salary structures, leave and attendance policies, exits and declaration checks."),
		PAYROLL(Area.PAYROLL, "Payroll dashboard", "Payroll", "wallet",
				"Monthly payroll inputs and runs, payslips and statutory compliance files."),
		FINANCE(Area.FINANCE, "Finance dashboard", "Finance", "bank",
				"Payroll approvals, bank transfer files, payments and finance reports."),
		SELF_SERVICE(Area.SELF_SERVICE, "My home", "Self-service", "user",
				"Your payslips, tax regime and declarations, leave, attendance and reimbursement claims.");

		private final Area area;

		private final String title;

		private final String label;

		private final String icon;

		private final String summary;

		Workspace(Area area, String title, String label, String icon, String summary) {
			this.area = area;
			this.title = title;
			this.label = label;
			this.icon = icon;
			this.summary = summary;
		}

		public String path() {
			return area.prefix();
		}

		public String title() {
			return title;
		}

		public String label() {
			return label;
		}

		public String icon() {
			return icon;
		}

		public String summary() {
			return summary;
		}

	}

	private static final DateTimeFormatter TODAY = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH);

	private final Clock clock;

	HomeController(Clock clock) {
		this.clock = clock;
	}

	@GetMapping("/admin")
	String admin(Authentication authentication, Model model) {
		return home(Workspace.ADMIN, authentication, model);
	}

	@GetMapping("/hr")
	String hr(Authentication authentication, Model model) {
		return home(Workspace.HR, authentication, model);
	}

	@GetMapping("/payroll")
	String payroll(Authentication authentication, Model model) {
		return home(Workspace.PAYROLL, authentication, model);
	}

	@GetMapping("/finance")
	String finance(Authentication authentication, Model model) {
		return home(Workspace.FINANCE, authentication, model);
	}

	@GetMapping("/me")
	String me(Authentication authentication, Model model) {
		return home(Workspace.SELF_SERVICE, authentication, model);
	}

	private String home(Workspace workspace, Authentication authentication, Model model) {
		Set<Role> roles = Role.fromAuthorities(authentication.getAuthorities());
		LocalDateTime now = LocalDateTime.now(clock);
		String name = KoshUserDetails.displayName(authentication);
		model.addAttribute("workspace", workspace);
		model.addAttribute("workspaces",
				Arrays.stream(Workspace.values()).filter(other -> other.area.allows(roles)).toList());
		model.addAttribute("today", now.format(TODAY));
		model.addAttribute("greeting", greeting(now.getHour()) + (name.contains("@") ? "" : ", " + firstName(name)));
		model.addAttribute("email", authentication.getName());
		model.addAttribute("roles", roles.stream().map(Role::label).toList());
		return "home/index";
	}

	static String greeting(int hour) {
		if (hour < 12) {
			return "Good morning";
		}
		return (hour < 17) ? "Good afternoon" : "Good evening";
	}

	/** "Meera" for "Meera Deshpande"; a one-word name stays whole. */
	static String firstName(String displayName) {
		return displayName.strip().split("\\s+")[0];
	}

}
