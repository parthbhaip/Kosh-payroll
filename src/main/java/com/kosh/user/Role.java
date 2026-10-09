package com.kosh.user;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.security.core.GrantedAuthority;

/** What a user may do in Kosh (CLAUDE.md > Roles). One user can hold several; everyone who works there is an EMPLOYEE. */
public enum Role {

	ADMIN("Admin", "Company settings, users and roles, statutory rules and the audit log."),
	HR("HR", "Employees, salary structures, leave and attendance policies, exits and declarations."),
	PAYROLL("Payroll", "Monthly payroll runs, payslips and statutory compliance files."),
	FINANCE("Finance", "Approves payroll, prepares bank files and marks payroll paid."),
	MANAGER("Manager", "Approves leave, attendance and claims for their own team. Never sees salaries."),
	EMPLOYEE("Employee", "Own payslips, tax declarations, leave and attendance. Everyone who works here has it.");

	private static final String PREFIX = "ROLE_";

	private final String label;

	private final String summary;

	Role(String label, String summary) {
		this.label = label;
		this.summary = summary;
	}

	public String label() {
		return label;
	}

	/** What the role lets a user do, for the role pickers. */
	public String summary() {
		return summary;
	}

	/** The Spring Security authority, {@code ROLE_HR}. */
	public String authority() {
		return PREFIX + name();
	}

	/** The role behind a granted authority, or empty for authorities that are not Kosh roles. */
	public static Optional<Role> fromAuthority(String authority) {
		for (Role role : values()) {
			if (role.authority().equals(authority)) {
				return Optional.of(role);
			}
		}
		return Optional.empty();
	}

	/** Role names for the audit log, in the order of {@link Role}. */
	public static List<String> labels(Collection<Role> roles) {
		return roles.stream().sorted().map(Role::label).toList();
	}

	/** The Kosh roles among granted authorities; anything else (remember-me or anonymous markers) is skipped. */
	public static Set<Role> fromAuthorities(Collection<? extends GrantedAuthority> authorities) {
		Set<Role> roles = EnumSet.noneOf(Role.class);
		for (GrantedAuthority authority : authorities) {
			fromAuthority(authority.getAuthority()).ifPresent(roles::add);
		}
		return roles;
	}

}
