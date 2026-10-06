package com.kosh.security;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;

import com.kosh.user.Role;

/**
 * The role-restricted URL areas (PLAN T07). {@link SecurityConfig} builds its URL rules from this table and the
 * sidebar uses it to show only the pages a user can open, so the two never disagree. Paths outside every area need a
 * signed-in user only; services still check ownership and team membership on top of these rules.
 */
public enum Area {

	ADMIN("/admin", Role.ADMIN),
	HR("/hr", Role.HR, Role.ADMIN),
	PAYROLL("/payroll", Role.PAYROLL, Role.ADMIN),
	FINANCE("/finance", Role.FINANCE, Role.ADMIN),
	TEAM("/team", Role.MANAGER),
	SELF_SERVICE("/me", Role.EMPLOYEE);

	private final String prefix;

	private final Set<Role> roles;

	Area(String prefix, Role... roles) {
		this.prefix = prefix;
		this.roles = Set.of(roles);
	}

	public String prefix() {
		return prefix;
	}

	public Set<Role> roles() {
		return roles;
	}

	public boolean allows(Collection<Role> userRoles) {
		return userRoles.stream().anyMatch(roles::contains);
	}

	/** The area a path belongs to: {@code /hr} and {@code /hr/employees} are in HR, {@code /hrx} is not. */
	public static Optional<Area> of(String path) {
		for (Area area : values()) {
			if (path.equals(area.prefix) || path.startsWith(area.prefix + "/")) {
				return Optional.of(area);
			}
		}
		return Optional.empty();
	}

}
