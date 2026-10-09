package com.kosh.user;

import java.util.EnumSet;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.kosh.audit.AuditAction;
import com.kosh.audit.AuditChanges;
import com.kosh.audit.AuditService;
import com.kosh.audit.AuditService.Actor;
import com.kosh.common.BusinessRuleException;

/**
 * On startup, when nobody can sign in as admin (no enabled ADMIN user with a password), makes {@code ADMIN_EMAIL} an
 * admin with {@code ADMIN_PASSWORD}: a new account, or the existing account with that e-mail gets the Admin role,
 * the password, and is enabled and unlocked. With an active admin present it does nothing, so the variables are only a
 * first-run and recovery tool; remove them after signing in. Without them it logs how to recover.
 */
@Component
class AdminBootstrap implements ApplicationRunner {

	enum Outcome {

		/** An active admin already exists; nothing changed. */
		NOT_NEEDED,
		/** No active admin and no ADMIN_EMAIL/ADMIN_PASSWORD; a warning was logged. */
		NOT_CONFIGURED,
		/** A new admin account was created. */
		CREATED,
		/** An existing account became an active admin. */
		PROMOTED

	}

	static final String DISPLAY_NAME = "Kosh administrator";

	private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

	private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

	private final UserRepository users;

	private final PasswordEncoder passwordEncoder;

	private final AuditService audit;

	private final String email;

	private final String password;

	AdminBootstrap(UserRepository users, PasswordEncoder passwordEncoder, AuditService audit,
			@Value("${app.admin-bootstrap.email:}") String email,
			@Value("${app.admin-bootstrap.password:}") String password) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.audit = audit;
		this.email = email;
		this.password = password;
	}

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		ensureAdmin(email, password);
	}

	/**
	 * @throws IllegalStateException (stops startup) when an admin is needed and the e-mail or password is unusable; the
	 * message never holds the password
	 */
	@Transactional
	Outcome ensureAdmin(String email, String password) {
		if (users.countActiveAdmins() > 0) {
			return Outcome.NOT_NEEDED;
		}
		if (email == null || email.isBlank() || password == null || password.isEmpty()) {
			log.warn("Nobody can sign in as admin. Set ADMIN_EMAIL and ADMIN_PASSWORD and restart to create one.");
			return Outcome.NOT_CONFIGURED;
		}
		String address = User.normalizeEmail(email);
		if (address.length() > 254 || !EMAIL.matcher(address).matches()) {
			throw new IllegalStateException("ADMIN_EMAIL is not an email address");
		}
		try {
			PasswordLinkService.checkPassword(password);
		}
		catch (BusinessRuleException rule) {
			throw new IllegalStateException("ADMIN_PASSWORD breaks the password rules: " + rule.getMessage());
		}
		User admin = users.findByEmailIgnoreCase(address).orElse(null);
		Outcome outcome = (admin == null) ? Outcome.CREATED : Outcome.PROMOTED;
		AuditChanges changes = AuditChanges.create();
		if (admin == null) {
			admin = new User(address, DISPLAY_NAME, Set.of(Role.ADMIN));
			changes.field("Roles", null, Role.ADMIN.label());
		}
		else {
			Set<Role> roles = EnumSet.copyOf(admin.getRoles());
			roles.add(Role.ADMIN);
			changes.field("Roles", Role.labels(admin.getRoles()), Role.labels(roles))
				.field("Status", admin.isEnabled() ? "Enabled" : "Disabled", "Enabled");
			admin.setRoles(roles);
			admin.setEnabled(true);
		}
		admin.setPasswordHash(passwordEncoder.encode(password));
		admin.clearLoginFailures();
		users.save(admin);
		audit.recordAs(Actor.SYSTEM, AuditAction.ADMIN_BOOTSTRAPPED, User.AUDIT_TYPE, admin.getId(),
				((outcome == Outcome.CREATED) ? "Created the admin account " : "Made an active admin of ") + address
						+ " from ADMIN_EMAIL, with the password from ADMIN_PASSWORD",
				changes);
		log.info("No active admin found, so {} can now sign in as admin with ADMIN_PASSWORD. Remove both variables now.",
				address);
		return outcome;
	}

}
