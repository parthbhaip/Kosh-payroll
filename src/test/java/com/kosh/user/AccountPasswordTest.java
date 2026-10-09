package com.kosh.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.kosh.audit.AuditAction;
import com.kosh.audit.AuditEvent;
import com.kosh.audit.AuditEventRepository;
import com.kosh.audit.AuditFilter;
import com.kosh.user.UserToken.Purpose;
import com.kosh.web.Flash;

/**
 * Changing your own password at {@code /account/password} (PLAN T09) through real sign-ins: the current password is
 * required, wrong guesses count towards the sign-in lock, and a change signs the user out everywhere else and spends
 * open reset links. Every test runs in a rolled-back transaction; {@code SignInAttemptsCommitTest} proves the counted
 * wrong guesses commit.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AccountPasswordTest {

	/** The fixed test clock. */
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 5, 10, 0);

	private static final String EMAIL = "kavya.pawar@account.kosh.test";

	private static final String PASSWORD = "Demo@1234";

	private static final String NEW_PASSWORD = "Panhala Fort 1660";

	private final MockMvc mvc;

	private final UserRepository users;

	private final PasswordEncoder passwordEncoder;

	private final AccountService service;

	private final PasswordLinkService links;

	private final AuditEventRepository events;

	private User kavya;

	@Autowired
	AccountPasswordTest(MockMvc mvc, UserRepository users, PasswordEncoder passwordEncoder, AccountService service,
			PasswordLinkService links, AuditEventRepository events) {
		this.mvc = mvc;
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.service = service;
		this.links = links;
		this.events = events;
	}

	@BeforeEach
	void kavyaHasAnAccount() {
		kavya = save(EMAIL, "Kavya Pawar", Role.EMPLOYEE);
	}

	@Test
	void anonymousVisitorsAreSentToSignIn() throws Exception {
		mvc.perform(get("/account/password")).andExpect(redirectedUrl("/login"));
		mvc.perform(post("/account/password").param("currentPassword", PASSWORD)
			.param("password", NEW_PASSWORD)
			.param("confirmPassword", NEW_PASSWORD)
			.with(csrf())).andExpect(redirectedUrl("/login"));
		assertThat(passwordEncoder.matches(PASSWORD, kavya.getPasswordHash())).isTrue();
	}

	@Test
	void everySignedInUserHasThePageAndTheMenuLink() throws Exception {
		User admin = save("asha.patil@account.kosh.test", "Asha Patil", Role.ADMIN);

		for (User user : new User[] { kavya, admin }) {
			String page = mvc.perform(get("/account/password").session(signIn(user.getEmail(), PASSWORD)))
				.andExpect(status().isOk())
				.andExpect(view().name("account/password"))
				.andReturn()
				.getResponse()
				.getContentAsString();
			assertThat(page).as(user.getEmail())
				.contains("<title>Change password | Kosh</title>")
				.contains("<li><a href=\"/home\">Home</a></li>")
				.contains("<a class=\"menu-item\" href=\"/account/password\">")
				.containsPattern("<form class=\"card\" method=\"post\" action=\"/account/password\" "
						+ "aria-labelledby=\"password-title\"\\s+novalidate>")
				.contains("<label class=\"label\" for=\"currentPassword\">Current password</label>")
				.contains("autocomplete=\"current-password\"")
				.contains("<label class=\"label\" for=\"password\">New password</label>")
				.contains("<label class=\"label\" for=\"confirmPassword\">Type the new one again</label>")
				.contains("value=\"" + user.getEmail() + "\" readonly hidden")
				.contains("5 wrong current passwords in a row lock your account for 15 minutes")
				.doesNotContain("aria-invalid");
		}
	}

	@Test
	void aChangeNeedsTheCurrentPasswordAndSignsOutOtherDevicesOnly() throws Exception {
		MockHttpSession here = signIn(EMAIL, PASSWORD);
		MockHttpSession phone = signIn(EMAIL, PASSWORD);
		String resetAskedEarlier = links.issue(kavya, Purpose.RESET).token();
		mvc.perform(get("/password/reset").param("token", resetAskedEarlier)).andExpect(view().name("auth/set-password"));

		MvcResult done = change(here, PASSWORD, NEW_PASSWORD, NEW_PASSWORD).andExpect(redirectedUrl("/account/password"))
			.andReturn();

		assertThat(done.getFlashMap().get(Flash.ATTRIBUTE)).isEqualTo(Flash.success("Your password is changed")
			.withText("Use it the next time you sign in. Kosh signed you out on your other devices."));
		assertThat(passwordEncoder.matches(NEW_PASSWORD, kavya.getPasswordHash())).isTrue();
		mvc.perform(get("/me").session(here)).andExpect(status().isOk()).andExpect(authenticated().withUsername(EMAIL));
		mvc.perform(get("/me").session(phone)).andExpect(redirectedUrl("/login?ended"));

		mvc.perform(post("/login").param("email", EMAIL).param("password", PASSWORD).with(csrf()))
			.andExpect(unauthenticated());
		mvc.perform(post("/login").param("email", EMAIL).param("password", NEW_PASSWORD).with(csrf()))
			.andExpect(authenticated().withUsername(EMAIL));
		// A reset link asked for before the change no longer works.
		mvc.perform(get("/password/reset").param("token", resetAskedEarlier)).andExpect(view().name("auth/link-invalid"));

		List<AuditEvent> trail = eventsOf(kavya);
		assertThat(trail).extracting(AuditEvent::getAction)
			.containsExactly(AuditAction.LOGIN_SUCCEEDED, AuditAction.LOGIN_SUCCEEDED, AuditAction.PASSWORD_CHANGED,
					AuditAction.LOGIN_FAILED, AuditAction.LOGIN_SUCCEEDED);
		AuditEvent changed = trail.get(2);
		assertThat(changed.getActorEmail()).isEqualTo(EMAIL);
		assertThat(changed.getActorName()).isEqualTo("Kavya Pawar");
		assertThat(changed.getSummary()).isEqualTo("Changed their password; their other sessions were signed out");
		assertThat(changed.getBeforeJson()).isNull();
	}

	@Test
	void eachFieldIsCheckedWithAPlainMessageAndNothingIsEchoed() throws Exception {
		MockHttpSession session = signIn(EMAIL, PASSWORD);

		assertThat(changePage(session, "", NEW_PASSWORD, NEW_PASSWORD)).contains("Enter your current password.")
			.contains("aria-invalid=\"true\" aria-describedby=\"currentPassword-error\"");
		assertThat(changePage(session, PASSWORD, NEW_PASSWORD, "Panhala Fort 1661")).contains("The passwords don’t match.")
			.doesNotContain(NEW_PASSWORD)
			.doesNotContain(PASSWORD);
		assertThat(changePage(session, PASSWORD, "short1", "short1")).contains("Use 8 to 64 characters.")
			.contains("aria-describedby=\"password-help password-error\"");
		assertThat(changePage(session, PASSWORD, PASSWORD, PASSWORD))
			.contains("Choose a password different from your current one.");
		assertThat(passwordEncoder.matches(PASSWORD, kavya.getPasswordHash())).isTrue();
		// None of these was a wrong current password.
		assertThat(kavya.getFailedLoginCount()).isZero();
	}

	@Test
	void wrongCurrentPasswordsCountTowardsTheSignInLock() throws Exception {
		MockHttpSession session = signIn(EMAIL, PASSWORD);

		for (int attempt = 1; attempt < User.MAX_FAILED_LOGINS; attempt++) {
			assertThat(changePage(session, "Wrong pass 2026", NEW_PASSWORD, NEW_PASSWORD))
				.contains("That isn’t your current password.");
		}
		assertThat(kavya.getFailedLoginCount()).isEqualTo(4);

		assertThat(changePage(session, "Wrong pass 2026", NEW_PASSWORD, NEW_PASSWORD))
			.contains("Too many wrong passwords, so your account is locked until 5 Oct 2026, 10:15 AM. Try again then.");
		assertThat(kavya.getLockedUntil()).isEqualTo(NOW.plusMinutes(15));

		// Locked: even the right current password is refused, and sign-in elsewhere is shut too.
		assertThat(changePage(session, PASSWORD, NEW_PASSWORD, NEW_PASSWORD)).contains("your account is locked until");
		assertThat(passwordEncoder.matches(PASSWORD, kavya.getPasswordHash())).isTrue();
		mvc.perform(post("/login").param("email", EMAIL).param("password", PASSWORD).with(csrf()))
			.andExpect(unauthenticated());

		// The lock is on record; the wrong guesses themselves are not sign-ins.
		List<AuditEvent> trail = eventsOf(kavya);
		assertThat(trail).extracting(AuditEvent::getAction)
			.containsExactly(AuditAction.LOGIN_SUCCEEDED, AuditAction.ACCOUNT_LOCKED, AuditAction.LOGIN_FAILED);
		assertThat(trail.get(1).getActorName()).isEqualTo("System");
		assertThat(trail.get(1).getSummary())
			.isEqualTo("Locked Kavya Pawar out for 15 minutes after 5 wrong passwords in a row");
	}

	@Test
	void changingThePasswordNeedsTheCsrfToken() throws Exception {
		MockHttpSession session = signIn(EMAIL, PASSWORD);

		mvc.perform(post("/account/password").session(session)
			.param("currentPassword", PASSWORD)
			.param("password", NEW_PASSWORD)
			.param("confirmPassword", NEW_PASSWORD)).andExpect(redirectedUrl("/login?expired"));
		assertThat(passwordEncoder.matches(PASSWORD, kavya.getPasswordHash())).isTrue();
	}

	@Test
	void theServiceRefusesCallersWhoAreNotSignedIn() {
		assertThatThrownBy(() -> service.changePassword(null, PASSWORD, NEW_PASSWORD, null))
			.isInstanceOf(AuthenticationCredentialsNotFoundException.class);
	}

	private User save(String email, String name, Role... roles) {
		User user = new User(email, name, Set.of(roles));
		user.setPasswordHash(passwordEncoder.encode(PASSWORD));
		return users.save(user);
	}

	private MockHttpSession signIn(String email, String password) throws Exception {
		MvcResult result = mvc.perform(post("/login").param("email", email).param("password", password).with(csrf()))
			.andExpect(authenticated())
			.andReturn();
		return (MockHttpSession) result.getRequest().getSession(false);
	}

	private ResultActions change(MockHttpSession session, String current, String password, String confirm)
			throws Exception {
		return mvc.perform(post("/account/password").session(session)
			.param("currentPassword", current)
			.param("password", password)
			.param("confirmPassword", confirm)
			.with(csrf()));
	}

	private String changePage(MockHttpSession session, String current, String password, String confirm)
			throws Exception {
		return change(session, current, password, confirm).andExpect(status().isOk())
			.andExpect(view().name("account/password"))
			.andReturn()
			.getResponse()
			.getContentAsString();
	}

	/** The user's audit events, oldest first. */
	private List<AuditEvent> eventsOf(User user) {
		return events
			.findAll(AuditEventRepository.matching(new AuditFilter(null, null, User.AUDIT_TYPE, user.getId(), null, null),
					null), AuditEventRepository.NEWEST_FIRST)
			.reversed();
	}

}
