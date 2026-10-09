package com.kosh.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import com.kosh.audit.AuditAction;
import com.kosh.audit.AuditEvent;
import com.kosh.audit.AuditEventRepository;
import com.kosh.audit.AuditFilter;
import com.kosh.user.Role;
import com.kosh.user.User;
import com.kosh.user.UserRepository;

/**
 * Sign-ins in the audit log (PLAN T10): every form and remember-me sign-in, every refused form sign-in with its reason,
 * and the lock after five wrong passwords, each with the client's address. Rolled back after each test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SignInAuditTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 5, 10, 0);

	private static final String PASSWORD = "Demo@1234";

	private static final String DOMAIN = "@signin.audit.kosh.test";

	/** Where the sign-ins for unknown accounts come from, to find their events. */
	private static final String UNKNOWN_IP = "198.51.100.9";

	private final MockMvc mvc;

	private final UserRepository users;

	private final PasswordEncoder passwordEncoder;

	private final AuditEventRepository events;

	private String passwordHash;

	@Autowired
	SignInAuditTest(MockMvc mvc, UserRepository users, PasswordEncoder passwordEncoder, AuditEventRepository events) {
		this.mvc = mvc;
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.events = events;
	}

	@BeforeEach
	void hashTheTestPasswordOnce() {
		passwordHash = passwordEncoder.encode(PASSWORD);
	}

	@Test
	void aSignInIsRecordedWithTheAccountAndTheClientAddress() throws Exception {
		User meera = save("meera.deshpande" + DOMAIN, "Meera Deshpande", Role.HR, Role.EMPLOYEE);

		mvc.perform(post("/login").param("email", " Meera.Deshpande" + DOMAIN.toUpperCase())
			.param("password", PASSWORD)
			.with(csrf())
			.with(from("198.51.100.4"))).andExpect(redirectedUrl("/hr"));

		AuditEvent signedIn = onlyEventOf(meera);
		assertThat(signedIn.getAction()).isEqualTo(AuditAction.LOGIN_SUCCEEDED);
		assertThat(signedIn.getSummary()).isEqualTo("Signed in");
		assertThat(signedIn.getActorEmail()).isEqualTo(meera.getEmail());
		assertThat(signedIn.getActorName()).isEqualTo("Meera Deshpande");
		assertThat(signedIn.getEntityType()).isEqualTo("User");
		assertThat(signedIn.getIpAddress()).isEqualTo("198.51.100.4");
		assertThat(signedIn.getOccurredAt()).isEqualTo(NOW);
	}

	@Test
	void aRememberedBrowserSigningInAgainIsRecordedToo() throws Exception {
		User meera = save("meera.deshpande" + DOMAIN, "Meera Deshpande", Role.HR, Role.EMPLOYEE);
		Cookie rememberMe = mvc
			.perform(post("/login").param("email", meera.getEmail())
				.param("password", PASSWORD)
				.param("remember-me", "on")
				.with(csrf()))
			.andReturn()
			.getResponse()
			.getCookie("remember-me");

		mvc.perform(get("/hr").cookie(rememberMe)).andExpect(status().isOk());

		assertThat(eventsOf(meera)).extracting(AuditEvent::getSummary)
			.containsExactly("Signed in", "Signed in from a remembered browser");
	}

	@Test
	void eachRefusedSignInIsRecordedWithItsReason() throws Exception {
		User meera = save("meera.deshpande" + DOMAIN, "Meera Deshpande", Role.HR, Role.EMPLOYEE);
		User rohan = save("rohan.kale" + DOMAIN, "Rohan Kale", Role.PAYROLL, Role.EMPLOYEE);
		rohan.setEnabled(false);
		User sunita = users.save(new User("sunita.more" + DOMAIN, "Sunita More", Set.of(Role.EMPLOYEE)));

		signIn(meera.getEmail(), "Wrong@1234");
		signIn(rohan.getEmail(), PASSWORD);
		signIn(sunita.getEmail(), PASSWORD);
		signIn(" Nobody" + DOMAIN.toUpperCase(), PASSWORD, UNKNOWN_IP);
		signIn("Kolhapur2026!", PASSWORD, UNKNOWN_IP);
		// A password with an @ passes the browser's e-mail check, so it can land in the e-mail box too.
		signIn(PASSWORD, "Wrong@1234", UNKNOWN_IP);

		assertRefused(meera, "Wrong password");
		assertRefused(rohan, "Refused: the account is disabled");
		assertRefused(sunita, "Refused: the invite has not been accepted yet");
		List<AuditEvent> all = events.findAll();
		assertThat(all.stream().filter(event -> UNKNOWN_IP.equals(event.getIpAddress()))).hasSize(3)
			.allSatisfy(event -> {
				assertThat(event.getAction()).isEqualTo(AuditAction.LOGIN_FAILED);
				assertThat(event.getActorEmail()).isNull();
				assertThat(event.getActorName()).isEqualTo("Unknown user");
				assertThat(event.getEntityType()).isNull();
				assertThat(event.getSummary()).isEqualTo("No account with this email");
			});
		// Text typed for an unknown account may be a password: no event keeps it in any column.
		assertThat(all.stream()
			.flatMap(event -> Stream.of(event.getActorEmail(), event.getActorName(), event.getEntityType(),
					event.getSummary(), event.getBeforeJson(), event.getAfterJson(), event.getIpAddress()))
			.filter(Objects::nonNull)
			.map(text -> text.toLowerCase(Locale.ROOT)))
			.noneMatch(text -> text.contains("demo@1234") || text.contains("kolhapur2026!")
					|| text.contains("nobody" + DOMAIN));
	}

	@Test
	void theFifthWrongPasswordLocksTheAccountAndTriesDuringTheLockAreRefused() throws Exception {
		User meera = save("meera.deshpande" + DOMAIN, "Meera Deshpande", Role.HR, Role.EMPLOYEE);

		for (int i = 0; i < User.MAX_FAILED_LOGINS; i++) {
			signIn(meera.getEmail(), "Wrong@1234");
		}
		signIn(meera.getEmail(), PASSWORD);

		List<AuditEvent> trail = eventsOf(meera);
		assertThat(trail).extracting(AuditEvent::getAction)
			.containsExactly(AuditAction.LOGIN_FAILED, AuditAction.LOGIN_FAILED, AuditAction.LOGIN_FAILED,
					AuditAction.LOGIN_FAILED, AuditAction.LOGIN_FAILED, AuditAction.ACCOUNT_LOCKED,
					AuditAction.LOGIN_FAILED);
		AuditEvent lock = trail.get(5);
		assertThat(lock.getActorName()).isEqualTo("System");
		assertThat(lock.getActorEmail()).isNull();
		assertThat(lock.getSummary()).isEqualTo("Locked Meera Deshpande out for 15 minutes after 5 wrong passwords in a row");
		assertThat(trail.getLast().getSummary()).isEqualTo("Refused: the account is locked");
	}

	private void signIn(String email, String password) throws Exception {
		signIn(email, password, "127.0.0.1");
	}

	private void signIn(String email, String password, String address) throws Exception {
		mvc.perform(post("/login").param("email", email).param("password", password).with(csrf()).with(from(address)))
			.andExpect(redirectedUrl("/login"));
	}

	private void assertRefused(User user, String reason) {
		AuditEvent refused = onlyEventOf(user);
		assertThat(refused.getAction()).isEqualTo(AuditAction.LOGIN_FAILED);
		assertThat(refused.getActorEmail()).isEqualTo(user.getEmail());
		assertThat(refused.getSummary()).isEqualTo(reason);
		assertThat(refused.getIpAddress()).isEqualTo("127.0.0.1");
	}

	private AuditEvent onlyEventOf(User user) {
		List<AuditEvent> found = eventsOf(user);
		assertThat(found).hasSize(1);
		return found.getFirst();
	}

	/** The user's events, oldest first. */
	private List<AuditEvent> eventsOf(User user) {
		return events
			.findAll(AuditEventRepository.matching(new AuditFilter(null, null, User.AUDIT_TYPE, user.getId(), null, null),
					null), AuditEventRepository.NEWEST_FIRST)
			.reversed();
	}

	private User save(String email, String name, Role... roles) {
		User user = new User(email, name, Set.of(roles));
		user.setPasswordHash(passwordHash);
		return users.save(user);
	}

	private static RequestPostProcessor from(String address) {
		return request -> {
			request.setRemoteAddr(address);
			return request;
		};
	}

}
