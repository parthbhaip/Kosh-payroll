package com.kosh.user;

import java.util.Optional;

/** What a user may do in Kosh (CLAUDE.md > Roles). One user can hold several; everyone who works there is an EMPLOYEE. */
public enum Role {

	ADMIN("Admin"), HR("HR"), PAYROLL("Payroll"), FINANCE("Finance"), MANAGER("Manager"), EMPLOYEE("Employee");

	private static final String PREFIX = "ROLE_";

	private final String label;

	Role(String label) {
		this.label = label;
	}

	public String label() {
		return label;
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

}
