package com.kosh.security;

import java.util.Collection;

import org.springframework.security.core.Authentication;

import com.kosh.user.Role;

/** Where a user lands after signing in: the home of their highest role (PLAN T07). */
public final class Landing {

	private Landing() {
	}

	/** ADMIN {@code /admin}, HR {@code /hr}, PAYROLL {@code /payroll}, FINANCE {@code /finance}, anyone else {@code /me}. */
	public static String pathFor(Collection<Role> roles) {
		if (roles.contains(Role.ADMIN)) {
			return "/admin";
		}
		if (roles.contains(Role.HR)) {
			return "/hr";
		}
		if (roles.contains(Role.PAYROLL)) {
			return "/payroll";
		}
		if (roles.contains(Role.FINANCE)) {
			return "/finance";
		}
		return "/me";
	}

	public static String pathFor(Authentication authentication) {
		return pathFor(Role.fromAuthorities(authentication.getAuthorities()));
	}

}
