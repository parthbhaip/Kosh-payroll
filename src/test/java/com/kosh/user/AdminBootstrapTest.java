package com.kosh.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.kosh.audit.AuditAction;
import com.kosh.audit.AuditEvent;
import com.kosh.audit.AuditEventRepository;
import com.kosh.audit.AuditFilter;
import com.kosh.audit.AuditService;
import com.kosh.user.AdminBootstrap.Outcome;

/**
 * The first admin from {@code ADMIN_EMAIL}/{@code ADMIN_PASSWORD} (PLAN T09), called directly inside a rolled-back
 * transaction: the test profile blanks both, so starting a test context never bootstraps anyone into the shared
 * in-memory database. That the variables reach {@code run()} is shown on a bootstrap with a mocked repository. MockMvc is unused; it only lets this class share the cached context of the other web tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminBootstrapTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 5, 10, 0);

	private static final String EMAIL = "owner@bootstrap.kosh.test";

	private static final String PASSWORD = "Shiroli MIDC 2026";

	private final AdminBootstrap bootstrap;

	private final UserRepository users;

	private final PasswordEncoder passwordEncoder;

	private final Environment environment;

	private final AuditEventRepository events;

	@Autowired
	AdminBootstrapTest(AdminBootstrap bootstrap, UserRepository users, PasswordEncoder passwordEncoder,
			Environment environment, AuditEventRepository events) {
		this.bootstrap = bootstrap;
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.environment = environment;
		this.events = events;
	}

	@Test
	void theVariablesFeedTheBootstrapAndTestsBlankThem() {
		assertThat(environment.getProperty("app.admin-bootstrap.email")).isEmpty();
		assertThat(environment.getProperty("app.admin-bootstrap.password")).isEmpty();
		assertThat(users.countActiveAdmins()).isZero();
	}

	@Test
	void startupPassesAdminEmailAndAdminPasswordToTheBootstrap() throws Exception {
		assertThat(PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties")))
			.containsEntry("app.admin-bootstrap.email", "${ADMIN_EMAIL:}")
			.containsEntry("app.admin-bootstrap.password", "${ADMIN_PASSWORD:}");

		UserRepository noAdmins = mock(UserRepository.class);
		when(noAdmins.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.empty());
		new AdminBootstrap(noAdmins, passwordEncoder, mock(AuditService.class), EMAIL, PASSWORD).run(null);

		ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
		verify(noAdmins).save(saved.capture());
		assertThat(saved.getValue().getEmail()).isEqualTo(EMAIL);
		assertThat(saved.getValue().getRoles()).containsExactly(Role.ADMIN);
		assertThat(passwordEncoder.matches(PASSWORD, saved.getValue().getPasswordHash())).isTrue();
	}

	@Test
	void withNoAdminTheAddressBecomesANewAdminWhoCanSignIn() {
		assertThat(bootstrap.ensureAdmin("  Owner@Bootstrap.kosh.test ", PASSWORD)).isEqualTo(Outcome.CREATED);

		User admin = users.findByEmailIgnoreCase(EMAIL).orElseThrow();
		assertThat(admin.getRoles()).containsExactly(Role.ADMIN);
		assertThat(admin.getDisplayName()).isEqualTo("Kosh administrator");
		assertThat(admin.isEnabled()).isTrue();
		assertThat(passwordEncoder.matches(PASSWORD, admin.getPasswordHash())).isTrue();
		assertThat(users.countActiveAdmins()).isEqualTo(1);
		AuditEvent created = onlyEventOf(admin);
		assertThat(created.getAction()).isEqualTo(AuditAction.ADMIN_BOOTSTRAPPED);
		assertThat(created.getActorName()).isEqualTo("System");
		assertThat(created.getSummary())
			.isEqualTo("Created the admin account owner@bootstrap.kosh.test from ADMIN_EMAIL, with the password from ADMIN_PASSWORD");
		assertThat(created.getAfterJson()).isEqualTo("{\"Roles\":\"Admin\"}").doesNotContain(PASSWORD);

		// From now on it does nothing, whatever the variables say.
		assertThat(bootstrap.ensureAdmin("someone.else@bootstrap.kosh.test", "Another pass 2026"))
			.isEqualTo(Outcome.NOT_NEEDED);
		assertThat(users.existsByEmailIgnoreCase("someone.else@bootstrap.kosh.test")).isFalse();
		assertThat(passwordEncoder.matches(PASSWORD, admin.getPasswordHash())).isTrue();
	}

	@Test
	void anExistingAccountIsEnabledUnlockedAndGivenTheAdminRole() {
		User owner = new User(EMAIL, "Vikram Jadhav", Set.of(Role.HR, Role.EMPLOYEE));
		owner.setPasswordHash(passwordEncoder.encode("Old password 1"));
		owner.setEnabled(false);
		owner.lockUntil(NOW.plusMinutes(10));
		users.save(owner);
		// An admin who cannot sign in (disabled, or invited without a password) does not count.
		User disabledAdmin = new User("old.admin@bootstrap.kosh.test", "Old Admin", Set.of(Role.ADMIN));
		disabledAdmin.setPasswordHash(passwordEncoder.encode("Old password 1"));
		disabledAdmin.setEnabled(false);
		users.save(disabledAdmin);
		users.save(new User("invited.admin@bootstrap.kosh.test", "Invited Admin", Set.of(Role.ADMIN)));

		assertThat(bootstrap.ensureAdmin(EMAIL, PASSWORD)).isEqualTo(Outcome.PROMOTED);

		assertThat(owner.getRoles()).containsExactlyInAnyOrder(Role.ADMIN, Role.HR, Role.EMPLOYEE);
		assertThat(owner.getDisplayName()).isEqualTo("Vikram Jadhav");
		assertThat(owner.isEnabled()).isTrue();
		assertThat(owner.isLockedAt(NOW)).isFalse();
		assertThat(passwordEncoder.matches(PASSWORD, owner.getPasswordHash())).isTrue();
		assertThat(users.countActiveAdmins()).isEqualTo(1);
		AuditEvent promoted = onlyEventOf(owner);
		assertThat(promoted.getSummary())
			.isEqualTo("Made an active admin of owner@bootstrap.kosh.test from ADMIN_EMAIL, with the password from ADMIN_PASSWORD");
		assertThat(promoted.getBeforeJson()).isEqualTo("{\"Roles\":\"HR, Employee\",\"Status\":\"Disabled\"}");
		assertThat(promoted.getAfterJson()).isEqualTo("{\"Roles\":\"Admin, HR, Employee\",\"Status\":\"Enabled\"}");
	}

	@Test
	void withoutTheVariablesNothingIsCreated() {
		assertThat(bootstrap.ensureAdmin("", "")).isEqualTo(Outcome.NOT_CONFIGURED);
		assertThat(bootstrap.ensureAdmin(EMAIL, null)).isEqualTo(Outcome.NOT_CONFIGURED);
		assertThat(bootstrap.ensureAdmin(null, PASSWORD)).isEqualTo(Outcome.NOT_CONFIGURED);
		assertThat(users.countActiveAdmins()).isZero();
	}

	@Test
	void anUnusableAddressOrPasswordStopsStartupWithoutPrintingThePassword() {
		assertThatThrownBy(() -> bootstrap.ensureAdmin("not-an-email", PASSWORD))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("ADMIN_EMAIL is not an email address");
		assertThatThrownBy(() -> bootstrap.ensureAdmin(EMAIL, "short1")).isInstanceOf(IllegalStateException.class)
			.hasMessage("ADMIN_PASSWORD breaks the password rules: Use 8 to 64 characters.")
			.hasMessageNotContaining("short1");
		assertThat(users.existsByEmailIgnoreCase(EMAIL)).isFalse();
	}

	private AuditEvent onlyEventOf(User user) {
		List<AuditEvent> found = events.findAll(
				AuditEventRepository.matching(new AuditFilter(null, null, User.AUDIT_TYPE, user.getId(), null, null), null));
		assertThat(found).hasSize(1);
		return found.getFirst();
	}

}
