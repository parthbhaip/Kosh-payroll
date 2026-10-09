package com.kosh.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.kosh.audit.AuditAction;
import com.kosh.audit.AuditEvent;
import com.kosh.audit.AuditEventRepository;
import com.kosh.audit.AuditFilter;
import com.kosh.user.Role;
import com.kosh.user.User;
import com.kosh.user.UserRepository;

/**
 * Lockout as it runs in production: no test transaction, so each sign-in event is handled in its own committed
 * transaction with the user's row locked. Wrong passwords arriving at the same moment are counted one at a time (no
 * version conflict turns into an error page) and lock the account once, and wrong current passwords on the
 * change-password page commit their count although the change is refused. The test user is deleted afterwards, because
 * this class commits to the shared in-memory database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SignInAttemptsCommitTest {

	/** The fixed test clock. */
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 5, 10, 0);

	private static final String EMAIL = "prakash.salunkhe@commit.kosh.test";

	private static final String PASSWORD = "Demo@1234";

	private final MockMvc mvc;

	private final UserRepository users;

	private final PasswordEncoder passwordEncoder;

	private final AuditEventRepository events;

	private final JdbcTemplate jdbc;

	@Autowired
	SignInAttemptsCommitTest(MockMvc mvc, UserRepository users, PasswordEncoder passwordEncoder,
			AuditEventRepository events, JdbcTemplate jdbc) {
		this.mvc = mvc;
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.events = events;
		this.jdbc = jdbc;
	}

	@BeforeEach
	void saveTheUser() {
		User user = new User(EMAIL, "Prakash Salunkhe", Set.of(Role.EMPLOYEE));
		user.setPasswordHash(passwordEncoder.encode(PASSWORD));
		users.save(user);
	}

	@AfterEach
	void deleteTheUserAndTheirAuditEvents() {
		users.findByEmailIgnoreCase(EMAIL).ifPresent(user -> {
			jdbc.update("delete from audit_events where entity_type = 'User' and entity_id = ?", user.getId());
			users.delete(user);
		});
	}

	@Test
	void wrongPasswordsAtTheSameMomentAreCountedOneAtATimeAndLockOnce() throws Exception {
		ExecutorService browsers = Executors.newFixedThreadPool(8);
		try {
			List<Callable<MockHttpServletResponse>> attempts = new ArrayList<>();
			for (int attempt = 0; attempt < 8; attempt++) {
				attempts.add(() -> signIn("Wrong@1234"));
			}
			for (Future<MockHttpServletResponse> answer : browsers.invokeAll(attempts)) {
				assertThat(answer.get().getRedirectedUrl()).isEqualTo("/login");
			}
		}
		finally {
			browsers.shutdown();
		}

		User saved = users.findByEmailIgnoreCase(EMAIL).orElseThrow();
		assertThat(saved.getLockedUntil()).isEqualTo(NOW.plusMinutes(15));
		// The fifth locked it and restarted the count; the rest arrived while it was locked.
		assertThat(saved.getFailedLoginCount()).isZero();
		// Every attempt is on record, and the lock exactly once.
		List<AuditEvent> trail = events.findAll(AuditEventRepository
			.matching(new AuditFilter(null, null, User.AUDIT_TYPE, saved.getId(), null, null), null));
		assertThat(trail).filteredOn(event -> event.getAction() == AuditAction.LOGIN_FAILED).hasSize(8);
		assertThat(trail).filteredOn(event -> event.getAction() == AuditAction.ACCOUNT_LOCKED).hasSize(1);
	}

	@Test
	void aSignInCommitsTheClearedCountAndTheTime() throws Exception {
		signIn("Wrong@1234");
		signIn("Wrong@1234");
		assertThat(users.findByEmailIgnoreCase(EMAIL).orElseThrow().getFailedLoginCount()).isEqualTo(2);

		assertThat(signIn(PASSWORD).getRedirectedUrl()).isEqualTo("/me");

		User saved = users.findByEmailIgnoreCase(EMAIL).orElseThrow();
		assertThat(saved.getFailedLoginCount()).isZero();
		assertThat(saved.getLastLoginAt()).isEqualTo(NOW);
	}

	@Test
	void wrongCurrentPasswordsOnTheChangePageCommitAndLock() throws Exception {
		MockHttpSession session = (MockHttpSession) mvc
			.perform(post("/login").param("email", EMAIL).param("password", PASSWORD).with(csrf()))
			.andReturn()
			.getRequest()
			.getSession(false);

		for (int attempt = 1; attempt <= User.MAX_FAILED_LOGINS; attempt++) {
			mvc.perform(post("/account/password").session(session)
				.param("currentPassword", "Wrong@1234")
				.param("password", "Panhala Fort 1660")
				.param("confirmPassword", "Panhala Fort 1660")
				.with(csrf())).andExpect(status().isOk());
		}

		User saved = users.findByEmailIgnoreCase(EMAIL).orElseThrow();
		assertThat(saved.getLockedUntil()).isEqualTo(NOW.plusMinutes(15));
		assertThat(passwordEncoder.matches(PASSWORD, saved.getPasswordHash())).isTrue();
		// The lock event survives the refusal thrown right after it.
		assertThat(events.findAll(AuditEventRepository.matching(
				new AuditFilter(null, AuditAction.ACCOUNT_LOCKED, User.AUDIT_TYPE, saved.getId(), null, null), null)))
			.hasSize(1);
	}

	private MockHttpServletResponse signIn(String password) throws Exception {
		return mvc.perform(post("/login").param("email", EMAIL).param("password", password).with(csrf()))
			.andReturn()
			.getResponse();
	}

}
