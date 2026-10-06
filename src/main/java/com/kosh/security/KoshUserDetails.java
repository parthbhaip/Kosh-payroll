package com.kosh.security;

import java.io.Serial;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import com.kosh.user.User;

/**
 * The signed-in user as Spring Security keeps it in the session: the e-mail as user name, the role authorities and
 * the display name for the app shell. Never holds the entity, and the password hash is erased after sign-in.
 */
public final class KoshUserDetails extends org.springframework.security.core.userdetails.User {

	@Serial
	private static final long serialVersionUID = 1L;

	private final String displayName;

	/** {@code locked}: the account is inside a sign-in lock at this moment. The user must have a password hash. */
	public KoshUserDetails(User user, boolean locked) {
		super(user.getEmail(), user.getPasswordHash(), user.isEnabled(), true, true, !locked,
				user.getRoles().stream().map(role -> new SimpleGrantedAuthority(role.authority())).toList());
		this.displayName = user.getDisplayName();
	}

	public String getDisplayName() {
		return displayName;
	}

	/** The person's name for a signed-in user; other principals (tests, remember-me markers) show their user name. */
	public static String displayName(Authentication authentication) {
		return (authentication.getPrincipal() instanceof KoshUserDetails user) ? user.getDisplayName()
				: authentication.getName();
	}

}
