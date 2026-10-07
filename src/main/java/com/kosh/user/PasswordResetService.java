package com.kosh.user;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import com.kosh.common.Fmt;
import com.kosh.config.AppProperties;
import com.kosh.notification.MailService;
import com.kosh.user.PasswordLinkService.Link;
import com.kosh.user.UserToken.Purpose;

/**
 * "Forgot your password?": e-mails a reset link (single use, 30 minutes) to an enabled account. The caller learns
 * nothing about whether the address has an account: the answer is the same, and the request only hands the address
 * to a background thread, which looks it up, issues the link and sends the e-mail. So neither the database work for a
 * known address nor a slow mail server makes known addresses answer slower. One e-mail per account per minute, so
 * the form cannot flood a mailbox. Invited users who never set a password may use it too (it proves the same
 * mailbox as their invite).
 */
@Service
public class PasswordResetService {

	/** A newer request within this time sends nothing; the link already sent still works. */
	static final Duration RESEND_AFTER = Duration.ofMinutes(1);

	private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

	private final UserRepository users;

	private final UserTokenRepository tokens;

	private final PasswordLinkService links;

	private final MailService mail;

	private final TaskExecutor mailSender;

	private final AppProperties app;

	private final Fmt fmt;

	private final Clock clock;

	public PasswordResetService(UserRepository users, UserTokenRepository tokens, PasswordLinkService links,
			MailService mail, @Qualifier("applicationTaskExecutor") TaskExecutor mailSender, AppProperties app, Fmt fmt,
			Clock clock) {
		this.users = users;
		this.tokens = tokens;
		this.links = links;
		this.mail = mail;
		this.mailSender = mailSender;
		this.app = app;
		this.fmt = fmt;
		this.clock = clock;
	}

	/**
	 * Hands the address to a background thread and returns at once, doing the same work for every address. The
	 * thread issues a reset link and e-mails it, or does nothing for an unknown or disabled address.
	 */
	public void requestReset(String email) {
		String address = User.normalizeEmail(email);
		mailSender.execute(() -> sendLink(address));
	}

	/** Not transactional on purpose: the link is saved (and committed) before the e-mail can arrive. */
	private void sendLink(String address) {
		try {
			Optional<User> found = users.findByEmailIgnoreCase(address).filter(User::isEnabled);
			if (found.isEmpty() || sentRecently(found.get())) {
				return;
			}
			User user = found.get();
			Link link = links.issue(user, Purpose.RESET);
			String body = """
					Hello %s,

					Someone asked to reset the password of your %s account (%s).

					Choose a new password here:
					%s/password/reset?token=%s

					The link works once and expires at %s IST, %d minutes from the request. If you didn't ask for this, ignore this email: your password stays the same.

					%s - Payroll that closes on time.
					""".formatted(user.getDisplayName(), app.brand(), user.getEmail(), app.baseUrl(), link.token(),
					fmt.dateTime(link.expiresAt()), Purpose.RESET.validity().toMinutes(), app.brand());
			mail.send(user.getEmail(), "Reset your " + app.brand() + " password", body);
		}
		catch (RuntimeException notSent) {
			// Never log the body: it holds the link.
			log.warn("Password reset email to {} was not sent: {}", address, notSent.getClass().getSimpleName());
		}
	}

	private boolean sentRecently(User user) {
		LocalDateTime cutoff = LocalDateTime.now(clock).minus(RESEND_AFTER);
		return tokens.findFirstByUserAndPurposeOrderByIdDesc(user, Purpose.RESET)
			.filter(last -> last.getCreatedAt().isAfter(cutoff))
			.isPresent();
	}

}
