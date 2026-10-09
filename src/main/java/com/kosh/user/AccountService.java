package com.kosh.user;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kosh.audit.AuditAction;
import com.kosh.audit.AuditService;
import com.kosh.audit.AuditService.Actor;
import com.kosh.common.BusinessRuleException;
import com.kosh.common.Fmt;
import com.kosh.common.NotFoundException;
import com.kosh.security.SignedInSessions;

/**
 * The signed-in user's own account. Changing the password needs the current one; a wrong current password counts
 * towards the same lock as a wrong sign-in, so a borrowed session cannot be used to guess it. A change signs the user
 * out everywhere else and spends any reset link still open.
 */
@Service
public class AccountService {

	static final String WRONG_CURRENT = "That isn’t your current password.";

	static final String SAME_PASSWORD = "Choose a password different from your current one.";

	private final UserRepository users;

	private final PasswordEncoder passwordEncoder;

	private final SignedInSessions sessions;

	private final PasswordLinkService links;

	private final AuditService audit;

	private final Fmt fmt;

	private final Clock clock;

	public AccountService(UserRepository users, PasswordEncoder passwordEncoder, SignedInSessions sessions,
			PasswordLinkService links, AuditService audit, Fmt fmt, Clock clock) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.sessions = sessions;
		this.links = links;
		this.audit = audit;
		this.fmt = fmt;
		this.clock = clock;
	}

	/**
	 * Replaces the signed-in user's own password, spends their open links and ends their other sessions
	 * ({@code keepSessionId} stays).
	 * A counted wrong guess must survive the refusal, hence no rollback for {@link BusinessRuleException}; nothing
	 * else changes before one is thrown.
	 * @throws BusinessRuleException on {@code currentPassword} (wrong, or locked) or {@code password} (rules, unchanged)
	 */
	@PreAuthorize("isAuthenticated()")
	@Transactional(noRollbackFor = BusinessRuleException.class)
	public void changePassword(Authentication self, String currentPassword, String newPassword, String keepSessionId) {
		User user = users.lockByEmailIgnoreCase(self.getName())
			.orElseThrow(() -> new NotFoundException("No account for the signed-in user"));
		LocalDateTime now = LocalDateTime.now(clock);
		if (user.isLockedAt(now)) {
			throw locked(user);
		}
		if (!matches(currentPassword, user)) {
			if (user.recordLoginFailure(now)) {
				audit.recordAs(Actor.SYSTEM, AuditAction.ACCOUNT_LOCKED, User.AUDIT_TYPE, user.getId(),
						user.lockedSummary(), null);
				throw locked(user);
			}
			throw new BusinessRuleException("currentPassword", WRONG_CURRENT);
		}
		PasswordLinkService.checkPassword(newPassword);
		if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
			throw new BusinessRuleException("password", SAME_PASSWORD);
		}
		user.setPasswordHash(passwordEncoder.encode(newPassword));
		user.clearLoginFailures();
		links.spendAllLinks(user);
		sessions.end(user.getEmail(), keepSessionId);
		audit.record(AuditAction.PASSWORD_CHANGED, User.AUDIT_TYPE, user.getId(),
				"Changed their password; their other sessions were signed out");
	}

	private boolean matches(String password, User user) {
		return password != null && !password.isEmpty() && user.getPasswordHash() != null
				&& passwordEncoder.matches(password, user.getPasswordHash());
	}

	private BusinessRuleException locked(User user) {
		return new BusinessRuleException("currentPassword",
				"Too many wrong passwords, so your account is locked until %s. Try again then."
					.formatted(fmt.dateTime(user.getLockedUntil())));
	}

}
