package com.kosh.security;

import java.util.Optional;

import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.RememberMeAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.kosh.audit.AuditAction;
import com.kosh.audit.AuditService;
import com.kosh.audit.AuditService.Actor;
import com.kosh.user.User;
import com.kosh.user.UserRepository;

/**
 * Puts every interactive sign-in in the audit log: form and remember-me sign-ins, and every refused form sign-in with
 * the reason (wrong password, no such account, invite not accepted, locked, disabled). Text typed for an unknown account
 * is never kept (only the client's address): it may be a password typed into the e-mail box, and passwords such as
 * {@code Demo@1234} look like e-mail addresses.
 */
@Component
class SignInAudit {

	private static final Actor UNKNOWN = new Actor(null, "Unknown user");

	private final AuditService audit;

	private final UserRepository users;

	SignInAudit(AuditService audit, UserRepository users) {
		this.audit = audit;
		this.users = users;
	}

	@EventListener
	@Transactional
	public void signedIn(AuthenticationSuccessEvent event) {
		Authentication signedIn = event.getAuthentication();
		boolean remembered = signedIn instanceof RememberMeAuthenticationToken;
		if (remembered || signedIn instanceof UsernamePasswordAuthenticationToken) {
			users.findByEmailIgnoreCase(signedIn.getName())
				.ifPresent(user -> audit.recordAs(actor(user), AuditAction.LOGIN_SUCCEEDED, User.AUDIT_TYPE, user.getId(),
						remembered ? "Signed in from a remembered browser" : "Signed in", null));
		}
	}

	/** Runs before {@link SignInAttempts}, so the wrong password that locks an account is listed before the lock. */
	@EventListener
	@Order(Ordered.HIGHEST_PRECEDENCE)
	@Transactional
	public void refused(AbstractAuthenticationFailureEvent event) {
		if (!(event.getAuthentication() instanceof UsernamePasswordAuthenticationToken typed)) {
			return;
		}
		Optional<User> account = users.findByEmailIgnoreCase(typed.getName());
		if (account.isPresent()) {
			User user = account.get();
			audit.recordAs(actor(user), AuditAction.LOGIN_FAILED, User.AUDIT_TYPE, user.getId(),
					reason(event, user), null);
		}
		else {
			audit.recordAs(UNKNOWN, AuditAction.LOGIN_FAILED, null, null, "No account with this email", null);
		}
	}

	private static String reason(AbstractAuthenticationFailureEvent event, User user) {
		if (event.getException() instanceof LockedException) {
			return "Refused: the account is locked";
		}
		if (event.getException() instanceof DisabledException) {
			return "Refused: the account is disabled";
		}
		if (event.getException() instanceof BadCredentialsException) {
			return (user.getPasswordHash() == null) ? "Refused: the invite has not been accepted yet" : "Wrong password";
		}
		return "Sign-in could not be completed";
	}

	private static Actor actor(User user) {
		return new Actor(user.getEmail(), user.getDisplayName());
	}

}
