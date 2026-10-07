package com.kosh.security;

import java.util.Objects;

import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

/**
 * Ends a user's signed-in sessions (registered by {@link SecurityConfig}): after an admin disables the account or
 * changes its roles, and after a password change or reset. An ended session is signed out on its next request, which
 * also cancels its remember-me cookie ({@link SecurityConfig#ENDED_URL}).
 */
@Component
public class SignedInSessions {

	private final SessionRegistry registry;

	public SignedInSessions(SessionRegistry registry) {
		this.registry = registry;
	}

	/** Ends every session of the user with this (normalised) e-mail except {@code keepSessionId}; null ends them all. */
	public void end(String email, String keepSessionId) {
		for (Object principal : registry.getAllPrincipals()) {
			if (principal instanceof UserDetails details && details.getUsername().equals(email)) {
				registry.getAllSessions(principal, false)
					.stream()
					.filter(session -> !Objects.equals(session.getSessionId(), keepSessionId))
					.forEach(SessionInformation::expireNow);
			}
		}
	}

}
