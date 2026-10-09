package com.kosh.user;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kosh.audit.AuditAction;
import com.kosh.audit.AuditChanges;
import com.kosh.audit.AuditService;
import com.kosh.common.BusinessRuleException;
import com.kosh.common.Fmt;
import com.kosh.common.NotFoundException;
import com.kosh.config.AppProperties;
import com.kosh.notification.MailService;
import com.kosh.security.KoshUserDetails;
import com.kosh.security.SignedInSessions;
import com.kosh.user.UserToken.Purpose;

/**
 * User administration for ADMIN users (PLAN T08): the user list, invites with a set-password link, name and role
 * changes, and enabling or disabling accounts. Kosh always keeps an active admin: nobody disables themselves or removes
 * their own Admin role, and the last active admin keeps the role. Disabling a user or changing their roles ends their
 * open sessions, so the change applies on their next request. Every change goes in the audit log.
 */
@Service
@PreAuthorize("hasRole('ADMIN')")
public class UserAdminService {

	public enum Status {

		ACTIVE("Active"), INVITED("Invited"), LOCKED("Locked"), DISABLED("Disabled");

		private final String label;

		Status(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}

		static Status of(User user, LocalDateTime now) {
			if (!user.isEnabled()) {
				return DISABLED;
			}
			if (user.getPasswordHash() == null) {
				return INVITED;
			}
			return user.isLockedAt(now) ? LOCKED : ACTIVE;
		}

	}

	/** A line in the user list. Roles in the order of {@link Role}. */
	public record Row(long id, String name, String email, List<Role> roles, Status status, LocalDateTime lastLoginAt) {

	}

	/**
	 * One user for the edit page. {@code inviteSentAt}/{@code inviteExpiresAt}: the latest invite while the user has no
	 * password, else null; {@code inviteExpired}: that link has run out. {@code self}: the signed-in admin is looking at
	 * their own account.
	 */
	public record Detail(long id, String name, String email, List<Role> roles, Status status, LocalDateTime lastLoginAt,
			LocalDateTime createdAt, LocalDateTime inviteSentAt, LocalDateTime inviteExpiresAt, boolean inviteExpired,
			boolean self) {

	}

	/** The result of a save: whose name, whether the roles changed, and whether it was the admin's own account. */
	public record Saved(String name, boolean rolesChanged, boolean self) {

	}

	static final String NEEDS_EMPLOYEE = "Add the Employee role. Only admin accounts can go without it.";

	static final String EMAIL_TAKEN = "A user with this email already exists.";

	static final String LAST_ADMIN = "Kosh needs at least one active admin. Give someone else the Admin role first.";

	private final UserRepository users;

	private final UserTokenRepository tokens;

	private final PasswordLinkService links;

	private final MailService mail;

	private final SignedInSessions sessions;

	private final AuditService audit;

	private final AppProperties app;

	private final Fmt fmt;

	private final Clock clock;

	public UserAdminService(UserRepository users, UserTokenRepository tokens, PasswordLinkService links,
			MailService mail, SignedInSessions sessions, AuditService audit, AppProperties app, Fmt fmt, Clock clock) {
		this.users = users;
		this.tokens = tokens;
		this.links = links;
		this.mail = mail;
		this.sessions = sessions;
		this.audit = audit;
		this.app = app;
		this.fmt = fmt;
		this.clock = clock;
	}

	/** Users whose name or e-mail contains {@code text}, holding {@code role} (null for any). Three queries a page. */
	@Transactional(readOnly = true)
	public Page<Row> list(String text, Role role, Pageable pageable) {
		Page<User> page = users.search(text, role, pageable);
		Map<Long, User> withRoles = page.isEmpty() ? Map.of()
				: users.findWithRolesByIdIn(page.map(User::getId).getContent())
					.stream()
					.collect(Collectors.toMap(User::getId, Function.identity()));
		LocalDateTime now = now();
		return page.map(user -> {
			User loaded = withRoles.get(user.getId());
			return new Row(loaded.getId(), loaded.getDisplayName(), loaded.getEmail(), sorted(loaded.getRoles()),
					Status.of(loaded, now), loaded.getLastLoginAt());
		});
	}

	@Transactional(readOnly = true)
	public Detail detail(long id, Authentication actor) {
		User user = find(id);
		Optional<UserToken> invite = (user.getPasswordHash() == null)
				? tokens.findFirstByUserAndPurposeOrderByIdDesc(user, Purpose.INVITE) : Optional.empty();
		LocalDateTime now = now();
		return new Detail(user.getId(), user.getDisplayName(), user.getEmail(), sorted(user.getRoles()),
				Status.of(user, now), user.getLastLoginAt(), user.getCreatedAt(),
				invite.map(UserToken::getCreatedAt).orElse(null), invite.map(UserToken::getExpiresAt).orElse(null),
				invite.map(link -> !now.isBefore(link.getExpiresAt())).orElse(false), isSelf(user, actor));
	}

	/**
	 * Creates the account without a password and e-mails a set-password link. A failed e-mail rolls the whole invite
	 * back, so nobody is added who never heard about it.
	 * @return the new user's id
	 */
	@Transactional
	public long invite(String name, String email, Set<Role> roles, Authentication actor) {
		checkRoles(roles);
		if (users.existsByEmailIgnoreCase(email)) {
			throw new BusinessRuleException("email", EMAIL_TAKEN);
		}
		User user;
		try {
			// IDENTITY ids insert at once, so a concurrent invite for the same e-mail fails here on uk_users_email.
			user = users.save(new User(email, name, roles));
		}
		catch (DataIntegrityViolationException taken) {
			throw new BusinessRuleException("email", EMAIL_TAKEN);
		}
		sendInvite(user, actor);
		audit.record(AuditAction.USER_INVITED, User.AUDIT_TYPE, user.getId(),
				"Invited %s (%s)".formatted(user.getDisplayName(), user.getEmail()),
				AuditChanges.create()
					.field("Name", null, user.getDisplayName())
					.field("Email", null, user.getEmail())
					.field("Roles", null, Role.labels(user.getRoles())));
		return user.getId();
	}

	/** Changes the name and roles. Changed roles end the user's sessions, unless it is the admin's own account. */
	@Transactional
	public Saved update(long id, String name, Set<Role> roles, Authentication actor) {
		User user = find(id);
		checkRoles(roles);
		boolean self = isSelf(user, actor);
		if (user.hasRole(Role.ADMIN) && !roles.contains(Role.ADMIN)) {
			if (self) {
				throw new BusinessRuleException("roles", "You can’t remove your own Admin role. Ask another admin to do it.");
			}
			keepAnActiveAdmin(user, "roles");
		}
		boolean rolesChanged = !user.getRoles().equals(Set.copyOf(roles));
		String oldName = user.getDisplayName();
		AuditChanges changes = AuditChanges.create().field("Roles", Role.labels(user.getRoles()), Role.labels(roles));
		user.setDisplayName(name);
		user.setRoles(roles);
		if (rolesChanged && !self) {
			endSessions(user);
		}
		changes.field("Name", oldName, user.getDisplayName());
		if (!changes.isEmpty()) {
			audit.record(rolesChanged ? AuditAction.ROLES_CHANGED : AuditAction.USER_UPDATED, User.AUDIT_TYPE,
					user.getId(), rolesChanged ? "Changed roles for " + user.getDisplayName()
							: "Renamed %s to %s".formatted(oldName, user.getDisplayName()),
					changes);
		}
		return new Saved(user.getDisplayName(), rolesChanged, self);
	}

	/** Stops sign-in and ends the user's open sessions. @return the user's name */
	@Transactional
	public String disable(long id, Authentication actor) {
		User user = find(id);
		if (isSelf(user, actor)) {
			throw new BusinessRuleException(null, "You can’t disable your own account.");
		}
		if (user.hasRole(Role.ADMIN)) {
			keepAnActiveAdmin(user, null);
		}
		user.setEnabled(false);
		endSessions(user);
		audit.record(AuditAction.USER_DISABLED, User.AUDIT_TYPE, user.getId(),
				"Disabled " + user.getDisplayName() + "; their open sessions were ended");
		return user.getDisplayName();
	}

	/** @return the user's name */
	@Transactional
	public String enable(long id) {
		User user = find(id);
		if (!user.isEnabled()) {
			user.setEnabled(true);
			audit.record(AuditAction.USER_ENABLED, User.AUDIT_TYPE, user.getId(), "Enabled " + user.getDisplayName());
		}
		return user.getDisplayName();
	}

	/** E-mails a new set-password link to an invited user; their earlier links stop working. @return the e-mail */
	@Transactional
	public String resendInvite(long id, Authentication actor) {
		User user = find(id);
		if (user.getPasswordHash() != null) {
			throw new BusinessRuleException(null, user.getDisplayName() + " has already set a password.");
		}
		if (!user.isEnabled()) {
			throw new BusinessRuleException(null, "Enable the account before sending a new invite.");
		}
		sendInvite(user, actor);
		audit.record(AuditAction.INVITE_RESENT, User.AUDIT_TYPE, user.getId(),
				"Sent a new invite to " + user.getEmail() + "; the earlier link stopped working");
		return user.getEmail();
	}

	private void sendInvite(User user, Authentication actor) {
		PasswordLinkService.Link link = links.issue(user, Purpose.INVITE);
		String roles = String.join(", ", Role.labels(user.getRoles()));
		String body = """
				Hello %s,

				%s has set up a %s account for you.

				Sign-in email: %s
				Roles: %s

				Choose your password here:
				%s/password/set?token=%s

				The link works once and expires on %s IST. If it has expired, ask your %s administrator to send a new invite.

				%s - Payroll that closes on time.
				""".formatted(user.getDisplayName(), KoshUserDetails.displayName(actor), app.brand(), user.getEmail(),
				roles, app.baseUrl(), link.token(), fmt.dateTime(link.expiresAt()), app.brand(), app.brand());
		mail.send(user.getEmail(), "You're invited to " + app.brand(), body);
	}

	/** At least one role, and Employee for everyone but admins: a user without it would land on a page they can't open. */
	static void checkRoles(Collection<Role> roles) {
		if (roles == null || roles.isEmpty()) {
			throw new BusinessRuleException("roles", "Choose at least one role.");
		}
		if (!roles.contains(Role.ADMIN) && !roles.contains(Role.EMPLOYEE)) {
			throw new BusinessRuleException("roles", NEEDS_EMPLOYEE);
		}
	}

	/**
	 * Refuses a change that would leave no active admin. ponytail: two admins demoting each other at the same instant
	 * can both pass this check; T09's ADMIN_EMAIL bootstrap recovers an install with no admin.
	 */
	private void keepAnActiveAdmin(User user, String field) {
		boolean activeAdmin = user.isEnabled() && user.getPasswordHash() != null;
		if (activeAdmin && users.countOtherActiveAdmins(user.getId()) == 0) {
			throw new BusinessRuleException(field, LAST_ADMIN);
		}
	}

	/** Expires every session of the user; each one is signed out on its next request. */
	private void endSessions(User user) {
		sessions.end(user.getEmail(), null);
	}

	private User find(long id) {
		return users.findById(id).orElseThrow(() -> new NotFoundException("No user with id " + id));
	}

	private static boolean isSelf(User user, Authentication actor) {
		return user.getEmail().equals(User.normalizeEmail(actor.getName()));
	}

	private static List<Role> sorted(Collection<Role> roles) {
		return roles.stream().sorted().toList();
	}

	private LocalDateTime now() {
		return LocalDateTime.now(clock);
	}

}
