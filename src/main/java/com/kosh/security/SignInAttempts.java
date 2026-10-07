package com.kosh.security;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.RememberMeAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import com.kosh.user.User;
import com.kosh.user.UserRepository;

/**
 * Lockout from the sign-in events the authentication manager publishes: a wrong password counts against the account
 * ({@link User#recordLoginFailure}: the fifth in a row locks it for 15 minutes) and a sign-in clears the count and
 * records when it happened. Unknown e-mails and invited accounts without a password count nothing. A locked account
 * is refused before its password is checked, so tries during a lock neither count nor extend it. The wrong password
 * that locks the account marks its request ({@link #LOCKED_NOW}) so the answer can say so at once. Only interactive
 * sign-ins (form and remember-me) are recorded, never per-request tokens such as an API's.
 */
@Component
class SignInAttempts {

	/** Request attribute: this request's wrong password has just locked the account. */
	static final String LOCKED_NOW = SignInAttempts.class.getName() + ".LOCKED_NOW";

	private final UserRepository users;

	private final Clock clock;

	SignInAttempts(UserRepository users, Clock clock) {
		this.users = users;
		this.clock = clock;
	}

	@EventListener
	@Transactional
	public void wrongPassword(AuthenticationFailureBadCredentialsEvent event) {
		// Remember-me cookies carry no password; a bad one is not a guess at the account's password.
		if (event.getAuthentication() instanceof UsernamePasswordAuthenticationToken typed) {
			LocalDateTime now = LocalDateTime.now(clock);
			users.lockByEmailIgnoreCase(typed.getName())
				.filter(user -> user.getPasswordHash() != null && !user.isLockedAt(now))
				.ifPresent(user -> {
					if (user.recordLoginFailure(now)) {
						markRequest();
					}
				});
		}
	}

	@EventListener
	@Transactional
	public void signedIn(AuthenticationSuccessEvent event) {
		if (event.getAuthentication() instanceof UsernamePasswordAuthenticationToken
				|| event.getAuthentication() instanceof RememberMeAuthenticationToken) {
			users.lockByEmailIgnoreCase(event.getAuthentication().getName())
				.ifPresent(user -> user.recordLoginSuccess(LocalDateTime.now(clock)));
		}
	}

	private static void markRequest() {
		RequestAttributes request = RequestContextHolder.getRequestAttributes();
		if (request != null) {
			request.setAttribute(LOCKED_NOW, Boolean.TRUE, RequestAttributes.SCOPE_REQUEST);
		}
	}

}
