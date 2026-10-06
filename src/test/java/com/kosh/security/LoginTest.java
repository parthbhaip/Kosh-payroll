package com.kosh.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Set;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.RememberMeAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.kosh.user.Role;
import com.kosh.user.User;
import com.kosh.user.UserRepository;

/**
 * Signing in through the real form against the users table: landing by highest role, wrong guesses that read the same
 * whether or not an account exists, remember-me, sign-out, expired CSRF tokens and the sign-in page itself. Each test
 * runs in a transaction that is rolled back, and MockMvc runs on the same thread, so the users are never committed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@RecordApplicationEvents
class LoginTest {

	private static final String PASSWORD = "Demo@1234";

	private static final String EMAIL = "asha.patil@login.kosh.test";

	private final MockMvc mvc;

	private final UserRepository users;

	private final PasswordEncoder passwordEncoder;

	private final KoshUserDetailsService userDetails;

	private final ApplicationContext context;

	private final Clock clock;

	private String passwordHash;

	@Autowired
	LoginTest(MockMvc mvc, UserRepository users, PasswordEncoder passwordEncoder, KoshUserDetailsService userDetails,
			ApplicationContext context, Clock clock) {
		this.mvc = mvc;
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.userDetails = userDetails;
		this.context = context;
		this.clock = clock;
	}

	@BeforeEach
	void hashTheTestPasswordOnce() {
		passwordHash = passwordEncoder.encode(PASSWORD);
	}

	@Test
	void signInPageHasEmailPasswordWithShowHideAndRememberMe() throws Exception {
		String page = mvc.perform(get("/login"))
			.andExpect(status().isOk())
			.andExpect(view().name("auth/login"))
			.andExpect(header().string("Content-Security-Policy", SecurityConfig.CONTENT_SECURITY_POLICY))
			.andExpect(header().string("X-Frame-Options", "DENY"))
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(page).contains("<title>Sign in | Kosh</title>")
			.contains("<h1 class=\"auth-title\" id=\"login-title\">Sign in to Kosh</h1>")
			.containsPattern("<form class=\"auth-form\" method=\"post\" action=\"/login\">"
					+ "<input type=\"hidden\" name=\"_csrf\" value=\"[^\"]+\"/>")
			.contains("<label class=\"label\" for=\"email\">Work email</label>")
			.containsPattern("<input class=\"input\" id=\"email\" name=\"email\" type=\"email\" autocomplete=\"username\" "
					+ "maxlength=\"254\" required\\s+value=\"\" autofocus=\"autofocus\">")
			.contains("<label class=\"label\" for=\"password\">Password</label>")
			.containsPattern("<input class=\"input\" id=\"password\" name=\"password\" type=\"password\" "
					+ "autocomplete=\"current-password\" required\\s*>")
			// The show/hide button is revealed by password-toggle.js; without JavaScript it stays hidden.
			.containsPattern("<button class=\"btn btn-ghost btn-sm password-toggle\" type=\"button\" aria-controls=\"password\""
					+ "\\s+aria-label=\"Show password\" data-password-toggle hidden>Show</button>")
			.contains("<script src=\"/js/password-toggle.js\" defer></script>")
			.contains("<input class=\"checkbox\" type=\"checkbox\" name=\"remember-me\" aria-describedby=\"remember-help\">")
			.contains("For 14 days on this device. Leave it off on a shared computer.")
			.contains("<button class=\"btn btn-primary auth-submit\" type=\"submit\">Sign in</button>")
			.contains("href=\"/help\">Read how accounts work</a>")
			// No sign-in button in the header of the sign-in page itself, and no message before a first attempt.
			.doesNotContain("href=\"/login\">Sign in</a>")
			.doesNotContain("class=\"flash ")
			.doesNotContain("aria-invalid");
		mvc.perform(get("/js/password-toggle.js"))
			.andExpect(status().isOk())
			.andExpect(content()
				.string(containsString("input.type = show ? 'text' : 'password';")));
	}

	@ParameterizedTest(name = "{0} lands on {1}")
	@CsvSource({ "ADMIN HR EMPLOYEE, /admin", "HR PAYROLL EMPLOYEE, /hr", "PAYROLL FINANCE EMPLOYEE, /payroll",
			"FINANCE EMPLOYEE, /finance", "MANAGER EMPLOYEE, /me", "EMPLOYEE, /me", "ADMIN, /admin" })
	void signInLandsOnTheHomeOfTheHighestRole(String roles, String landing) throws Exception {
		Role[] granted = Arrays.stream(roles.split(" ")).map(Role::valueOf).toArray(Role[]::new);
		save(EMAIL, "Asha Patil", granted);

		MvcResult result = signIn(EMAIL, PASSWORD)
			.andExpect(redirectedUrl(landing))
			.andExpect(authenticated().withUsername(EMAIL).withRoles(roles.split(" ")))
			.andReturn();

		// The landing page opens for that user, with their name in the shell.
		String page = mvc.perform(get(landing).session(session(result)))
			.andExpect(status().isOk())
			.andExpect(view().name("home/index"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(page).contains("Good morning, Asha")
			.contains("aria-label=\"Account menu for Asha Patil\"")
			.contains("<span class=\"avatar\" aria-hidden=\"true\">AP</span>");
	}

	@Test
	void emailIsMatchedInAnyCaseAndWithSpaces() throws Exception {
		save(EMAIL, "Asha Patil", Role.HR, Role.EMPLOYEE);

		signIn("  Asha.PATIL@Login.Kosh.Test ", PASSWORD).andExpect(redirectedUrl("/hr"))
			.andExpect(authenticated().withUsername(EMAIL));
	}

	@Test
	void wrongPasswordUnknownEmailAndInvitedUserGetTheSameAnswer() throws Exception {
		save(EMAIL, "Asha Patil", Role.EMPLOYEE);
		User invited = new User("invited@login.kosh.test", "Rohan Kulkarni", Set.of(Role.EMPLOYEE));
		users.save(invited);

		for (String[] attempt : new String[][] { { EMAIL, "Wrong@1234" }, { "nobody@login.kosh.test", PASSWORD },
				{ "invited@login.kosh.test", PASSWORD }, { EMAIL, "" } }) {
			assertThat(rejected(signIn(attempt[0], attempt[1]))).as(attempt[0])
				.contains("<p class=\"flash-title\">The email or password is incorrect.</p>")
				.contains("value=\"" + attempt[0] + "\"");
		}
	}

	@Test
	void failedSignInShowsTheReasonOnceAndKeepsTheEmailButNotThePassword() throws Exception {
		save(EMAIL, "Asha Patil", Role.EMPLOYEE);
		ResultActions failed = signIn(" " + EMAIL + " ", "Wrong@1234");

		String page = rejected(failed);
		assertThat(page).contains("<div class=\"auth-notice\" id=\"login-notice\"><div class=\"flash flash-danger\" role=\"alert\">")
			.contains("<p class=\"flash-title\">The email or password is incorrect.</p>")
			.containsPattern("id=\"email\" name=\"email\" type=\"email\" autocomplete=\"username\" maxlength=\"254\" required\\s+"
					+ "value=\"asha.patil@login.kosh.test\" aria-invalid=\"true\" aria-describedby=\"login-notice\">")
			// Focus moves to the password, which is never sent back.
			.containsPattern("type=\"password\" autocomplete=\"current-password\" required\\s+autofocus=\"autofocus\" "
					+ "aria-invalid=\"true\" aria-describedby=\"login-notice\">")
			.doesNotContain("Wrong@1234");

		// The flash is spent: reloading the page shows a clean form.
		mvc.perform(get("/login").session(session(failed.andReturn())))
			.andExpect(content()
				.string(not(containsString("flash-danger"))));
	}

	@Test
	void disabledUserIsRejectedButOnlyToldSoWithTheRightPassword() throws Exception {
		User user = save(EMAIL, "Asha Patil", Role.HR, Role.EMPLOYEE);
		user.setEnabled(false);

		assertThat(rejected(signIn(EMAIL, PASSWORD)))
			.contains("<p class=\"flash-title\">This account is disabled. Contact your Kosh administrator.</p>");
		// A wrong guess must not reveal that the account exists.
		assertThat(rejected(signIn(EMAIL, "Wrong@1234")))
			.contains("<p class=\"flash-title\">The email or password is incorrect.</p>");
	}

	@Test
	void lockedUserIsRejectedEvenWithTheRightPassword() throws Exception {
		User user = save(EMAIL, "Asha Patil", Role.EMPLOYEE);
		user.lockUntil(LocalDateTime.now(clock).plusMinutes(10));

		assertThat(rejected(signIn(EMAIL, PASSWORD))).contains(
				"<p class=\"flash-title\">This account is locked after too many failed sign-ins. Try again later.</p>");

		// The lock ends at its time.
		user.lockUntil(LocalDateTime.now(clock));
		signIn(EMAIL, PASSWORD).andExpect(authenticated().withUsername(EMAIL));
	}

	@Test
	void overlongPasswordIsJustAWrongPassword() throws Exception {
		save(EMAIL, "Asha Patil", Role.EMPLOYEE);

		assertThat(rejected(signIn(EMAIL, "x".repeat(500))))
			.contains("<p class=\"flash-title\">The email or password is incorrect.</p>");
	}

	@Test
	void passwordsAreStoredAsBcrypt() {
		assertThat(passwordHash).startsWith("{bcrypt}$2a$10$").doesNotContain(PASSWORD);
		assertThat(passwordEncoder.matches(PASSWORD, passwordHash)).isTrue();
	}

	@Test
	void signInWithoutACsrfTokenMeansTheSessionExpired() throws Exception {
		save(EMAIL, "Asha Patil", Role.EMPLOYEE);

		mvc.perform(post("/login").param("email", EMAIL).param("password", PASSWORD))
			.andExpect(redirectedUrl("/login?expired"))
			.andExpect(unauthenticated());
		String page = mvc.perform(get("/login").param("expired", ""))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(page).contains("<div class=\"flash flash-warning\" role=\"status\">")
			.contains("Your session expired. Sign in again to continue.");
	}

	@Test
	void aSignedInUserWithAStaleFormGoesHomeAndIsToldItExpired() throws Exception {
		save(EMAIL, "Asha Patil", Role.FINANCE, Role.EMPLOYEE);
		MockHttpSession session = session(signIn(EMAIL, PASSWORD).andReturn());

		mvc.perform(post("/finance").session(session).with(csrf().useInvalidToken()))
			.andExpect(redirectedUrl("/login?expired"));
		mvc.perform(get("/login").param("expired", "").session(session)).andExpect(redirectedUrl("/finance"));
		String page = mvc.perform(get("/finance").session(session))
			.andExpect(status().isOk())
			.andExpect(authenticated().withUsername(EMAIL))
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(page).contains("<div class=\"flash flash-warning\" role=\"status\">")
			.contains("<p class=\"flash-title\">That form had expired, so nothing was saved. Try again.</p>");

		// Shown once; opening the sign-in page while signed in says nothing.
		mvc.perform(get("/login").session(session)).andExpect(redirectedUrl("/finance"));
		mvc.perform(get("/finance").session(session)).andExpect(content().string(not(containsString("flash-warning"))));
	}

	@Test
	void signOutIsAPostThatEndsTheSession() throws Exception {
		save(EMAIL, "Asha Patil", Role.HR, Role.EMPLOYEE);
		MockHttpSession session = session(signIn(EMAIL, PASSWORD).andReturn());
		mvc.perform(get("/hr").session(session)).andExpect(status().isOk());

		// Without the form's token nothing happens apart from a trip home with an "expired" notice.
		mvc.perform(post("/logout").session(session)).andExpect(redirectedUrl("/login?expired"));
		mvc.perform(get("/hr").session(session)).andExpect(status().isOk());

		mvc.perform(post("/logout").session(session).with(csrf()))
			.andExpect(redirectedUrl("/login?logout"))
			.andExpect(unauthenticated());
		assertThat(session.isInvalid()).isTrue();
		mvc.perform(get("/login").param("logout", ""))
			.andExpect(content()
				.string(containsString("<p class=\"flash-title\">You’ve signed out.</p>")));
	}

	@Test
	void signedInUsersAreSentHomeFromTheSignInPageAndFromHome() throws Exception {
		save(EMAIL, "Asha Patil", Role.FINANCE, Role.EMPLOYEE);
		MockHttpSession session = session(signIn(EMAIL, PASSWORD).andReturn());

		mvc.perform(get("/login").session(session)).andExpect(redirectedUrl("/finance"));
		mvc.perform(get("/home").session(session)).andExpect(redirectedUrl("/finance"));
		mvc.perform(get("/home")).andExpect(redirectedUrl("/login"));
	}

	@Test
	void aLinkOpenedBeforeSigningInIsWhereSignInGoes() throws Exception {
		save(EMAIL, "Asha Patil", Role.PAYROLL, Role.EMPLOYEE);
		MockHttpSession session = new MockHttpSession();

		mvc.perform(get("/me").session(session)).andExpect(redirectedUrl("/login"));
		mvc.perform(post("/login").session(session).param("email", EMAIL).param("password", PASSWORD).with(csrf()))
			.andExpect(header().string("Location", endsWith("/me?continue")));
	}

	@Test
	void rememberMeKeepsTheUserSignedInWithoutASessionUntilTheAccountIsDisabled() throws Exception {
		User user = save(EMAIL, "Asha Patil", Role.HR, Role.EMPLOYEE);

		signIn(EMAIL, PASSWORD).andExpect(cookie().doesNotExist("remember-me"));
		MvcResult result = mvc
			.perform(post("/login").param("email", EMAIL).param("password", PASSWORD).param("remember-me", "on").with(csrf()))
			.andExpect(redirectedUrl("/hr"))
			.andExpect(cookie().maxAge("remember-me", 14 * 24 * 60 * 60))
			.andExpect(cookie().httpOnly("remember-me", true))
			.andReturn();
		Cookie rememberMe = result.getResponse().getCookie("remember-me");

		// A new browser session with only the cookie is signed in as the same user.
		mvc.perform(get("/hr").cookie(rememberMe))
			.andExpect(status().isOk())
			.andExpect(authenticated().withUsername(EMAIL)
				.withAuthentication(auth -> assertThat(auth).isInstanceOf(RememberMeAuthenticationToken.class)));

		// Signing out clears the cookie.
		mvc.perform(post("/logout").cookie(rememberMe).with(csrf())).andExpect(cookie().maxAge("remember-me", 0));

		// A disabled account no longer gets in with an old cookie.
		user.setEnabled(false);
		mvc.perform(get("/hr").cookie(rememberMe)).andExpect(redirectedUrl("/login")).andExpect(unauthenticated());
	}

	@Test
	void signInsAreAnnouncedForAuditAndLockout(ApplicationEvents events) throws Exception {
		save(EMAIL, "Asha Patil", Role.EMPLOYEE);

		signIn(EMAIL, "Wrong@1234");
		signIn(EMAIL, PASSWORD);

		assertThat(events.stream(AuthenticationFailureBadCredentialsEvent.class)).hasSize(1);
		assertThat(events.stream(AuthenticationSuccessEvent.class)).singleElement()
			.extracting(event -> event.getAuthentication().getName())
			.isEqualTo(EMAIL);
	}

	@Test
	void userDetailsCarryTheDisplayNameAndNoInvitedAccounts() {
		save(EMAIL, "Asha Patil", Role.HR, Role.EMPLOYEE);
		users.save(new User("invited@login.kosh.test", "Rohan Kulkarni", Set.of(Role.EMPLOYEE)));

		KoshUserDetails details = userDetails.loadUserByUsername("ASHA.PATIL@login.kosh.test");
		assertThat(details.getUsername()).isEqualTo(EMAIL);
		assertThat(details.getDisplayName()).isEqualTo("Asha Patil");
		assertThat(details.getAuthorities()).extracting(Object::toString)
			.containsExactlyInAnyOrder("ROLE_HR", "ROLE_EMPLOYEE");
		assertThat(details.isEnabled()).isTrue();
		assertThat(details.isAccountNonLocked()).isTrue();
		assertThatThrownBy(() -> userDetails.loadUserByUsername("invited@login.kosh.test"))
			.isInstanceOf(UsernameNotFoundException.class);
	}

	@Test
	void theH2ConsoleChainOnlyExistsWhereTheConsoleIsOn() {
		assertThat(context.getBeansOfType(SecurityFilterChain.class)).containsOnlyKeys("webSecurity");
	}

	private User save(String email, String displayName, Role... roles) {
		User user = new User(email, displayName, Set.of(roles));
		user.setPasswordHash(passwordHash);
		return users.save(user);
	}

	private ResultActions signIn(String email, String password) throws Exception {
		return mvc.perform(post("/login").param("email", email).param("password", password).with(csrf()));
	}

	/** A failed sign-in goes back to the sign-in page; follow it in the same session and return that page. */
	private String rejected(ResultActions signIn) throws Exception {
		MvcResult result = signIn.andExpect(redirectedUrl("/login")).andExpect(unauthenticated()).andReturn();
		return mvc.perform(get("/login").session(session(result)))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
	}

	private static MockHttpSession session(MvcResult result) {
		return (MockHttpSession) result.getRequest().getSession(false);
	}

}
