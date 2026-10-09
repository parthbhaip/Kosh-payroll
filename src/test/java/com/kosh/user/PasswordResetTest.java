package com.kosh.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.kosh.audit.AuditAction;
import com.kosh.audit.AuditEvent;
import com.kosh.audit.AuditEventRepository;
import com.kosh.audit.AuditFilter;
import com.kosh.common.HmacHasher;
import com.kosh.notification.MailService;
import com.kosh.user.UserToken.Purpose;

/**
 * "Forgot your password?" through the real pages (PLAN T09): the same answer whether or not the address has an
 * account, a hashed single-use link that works for 30 minutes, the new password, and what a reset does to locks and
 * open sessions. Mail is a spy on the log transport. A reset request is handled on a background thread (lookup, link
 * and e-mail), which cannot see uncommitted rows, so this class runs WITHOUT a test transaction: the tests wait for
 * the e-mail, re-read users from the database, and delete their users and links afterwards.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@MockitoSpyBean(types = MailService.class)
class PasswordResetTest {

	/** The fixed test clock. */
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 5, 10, 0);

	private static final String PASSWORD = "Demo@1234";

	private static final String NEW_PASSWORD = "Rankala Lake 2026";

	/** Every user this class saves has an address here; they are deleted after each test. */
	private static final String DOMAIN = "@reset.kosh.test";

	private static final String EMAIL = "meera.deshpande" + DOMAIN;

	private static final String SUBJECT = "Reset your Kosh password";

	private static final Pattern LINK = Pattern
		.compile("http://localhost:8080/password/reset\\?token=([A-Za-z0-9_-]{43})");

	private final MockMvc mvc;

	private final UserRepository users;

	private final UserTokenRepository tokens;

	private final MailService mail;

	private final HmacHasher hasher;

	private final PasswordEncoder passwordEncoder;

	private final AuditEventRepository events;

	private final JdbcTemplate jdbc;

	private User meera;

	@Autowired
	PasswordResetTest(MockMvc mvc, UserRepository users, UserTokenRepository tokens, MailService mail, HmacHasher hasher,
			PasswordEncoder passwordEncoder, AuditEventRepository events, JdbcTemplate jdbc) {
		this.mvc = mvc;
		this.users = users;
		this.tokens = tokens;
		this.mail = mail;
		this.hasher = hasher;
		this.passwordEncoder = passwordEncoder;
		this.events = events;
		this.jdbc = jdbc;
	}

	@BeforeEach
	void meeraHasAnAccount() {
		meera = new User(EMAIL, "Meera Deshpande", Set.of(Role.HR, Role.EMPLOYEE));
		meera.setPasswordHash(passwordEncoder.encode(PASSWORD));
		users.save(meera);
	}

	@AfterEach
	void deleteTheUsersAndTheirLinks() {
		List<User> created = users.findAll().stream().filter(user -> user.getEmail().endsWith(DOMAIN)).toList();
		List<Long> ids = created.stream().map(User::getId).toList();
		tokens.deleteAll(tokens.findAll().stream().filter(link -> ids.contains(link.getUser().getId())).toList());
		ids.forEach(id -> jdbc.update("delete from audit_events where entity_type = 'User' and entity_id = ?", id));
		users.deleteAll(created);
	}

	// ------------------------------------------------------------------ asking for a link

	@Test
	void signInPageLinksToTheForgotPasswordForm() throws Exception {
		assertThat(page("/login")).contains("<a href=\"/password/forgot\">Forgot your password?</a>")
			.doesNotContain("Contact HR or your Kosh administrator");

		String form = mvc.perform(get("/password/forgot"))
			.andExpect(status().isOk())
			.andExpect(view().name("auth/forgot-password"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(form).contains("<title>Reset your password | Kosh</title>")
			.contains("<h1 class=\"auth-title\" id=\"forgot-title\">Reset your password</h1>")
			.containsPattern("<form class=\"auth-form\" method=\"post\" action=\"/password/forgot\" novalidate>"
					+ "<input type=\"hidden\" name=\"_csrf\" value=\"[^\"]+\"/>")
			.contains("<label class=\"label\" for=\"email\">Work email</label>")
			.containsPattern("id=\"email\" name=\"email\" type=\"email\" autocomplete=\"username\" maxlength=\"254\" required")
			.contains("<button class=\"btn btn-primary auth-submit\" type=\"submit\">Email me a link</button>")
			.contains("<a href=\"/login\">Sign in</a>")
			.doesNotContain("aria-invalid");
	}

	@Test
	void aKnownAddressGetsAHashedLinkThatWorksForThirtyMinutes() throws Exception {
		MvcResult sent = requestLink("  Meera.Deshpande@RESET.kosh.test ");

		String page = sentPage(sent);
		assertThat(page).contains("<title>Check your email | Kosh</title>")
			.contains("If <strong>meera.deshpande@reset.kosh.test</strong>")
			.contains("a link to reset its password is on its way.")
			.contains("The link works once, for 30 minutes.")
			.contains("<a href=\"/password/forgot\">Send a new link</a>");

		String body = sentBody();
		assertThat(body).startsWith("Hello Meera Deshpande,")
			.contains("Someone asked to reset the password of your Kosh account (meera.deshpande@reset.kosh.test).")
			.contains("The link works once and expires at 5 Oct 2026, 10:30 AM IST, 30 minutes from the request.")
			.contains("If you didn't ask for this, ignore this email: your password stays the same.");
		String token = token(body);
		// Only the keyed hash is stored, never the token itself.
		UserToken link = tokens.findByTokenHashAndPurpose(hasher.hash(token), Purpose.RESET).orElseThrow();
		assertThat(link.getExpiresAt()).isEqualTo(NOW.plusMinutes(30));
		assertThat(link.getUser().getId()).isEqualTo(meera.getId());
		assertThat(link.getUsedAt()).isNull();
	}

	@Test
	void unknownAndDisabledAddressesGetTheSameAnswerAndNoEmail() throws Exception {
		User off = new User("sunita.more@reset.kosh.test", "Sunita More", Set.of(Role.EMPLOYEE));
		off.setPasswordHash(passwordEncoder.encode(PASSWORD));
		off.setEnabled(false);
		users.save(off);

		for (String address : new String[] { "nobody@reset.kosh.test", "sunita.more@reset.kosh.test" }) {
			String page = sentPage(requestLink(address));
			assertThat(page).as(address)
				.contains("If <strong>" + address + "</strong>")
				.contains("a link to reset its password is on its way.");
		}
		verify(mail, after(500).never()).send(anyString(), anyString(), anyString());
		assertThat(tokens.findAll()).noneMatch(link -> link.getPurpose() == Purpose.RESET);
	}

	@Test
	void theSentPageWithoutTheFlashStillReadsTheSameWithoutAnAddress() throws Exception {
		String page = mvc.perform(get("/password/forgot").param("sent", ""))
			.andExpect(view().name("auth/reset-sent"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(page).contains("If the address you entered belongs to a Kosh account")
			.doesNotContain("If <strong>");
	}

	@Test
	void theAddressIsCheckedBeforeAnythingIsSent() throws Exception {
		for (String[] input : new String[][] { { "", "Enter your work email." },
				{ "meera at reset", "Enter an email like name@company.in." },
				{ "a".repeat(250) + "@x.in", "Use at most 254 characters." } }) {
			String page = mvc.perform(post("/password/forgot").param("email", input[0]).with(csrf()))
				.andExpect(status().isOk())
				.andExpect(view().name("auth/forgot-password"))
				.andReturn()
				.getResponse()
				.getContentAsString();
			assertThat(page).as(input[0])
				.contains(input[1])
				.contains("aria-invalid=\"true\" aria-describedby=\"email-error\"");
		}
		verify(mail, after(300).never()).send(anyString(), anyString(), anyString());
	}

	@Test
	void askingTwiceWithinAMinuteSendsOneEmailAndTheFirstLinkKeepsWorking() throws Exception {
		requestLink(EMAIL);
		String token = token(sentBody());
		requestLink(EMAIL);

		verify(mail, after(500).times(1)).send(eq(EMAIL), eq(SUBJECT), anyString());
		mvc.perform(get("/password/reset").param("token", token)).andExpect(view().name("auth/set-password"));
	}

	@Test
	void aMailServerFailureLooksTheSameToTheVisitor() throws Exception {
		doThrow(new MailSendException("SMTP down")).when(mail).send(eq(EMAIL), anyString(), anyString());

		assertThat(sentPage(requestLink(EMAIL))).contains("a link to reset its password is on its way.");
		verify(mail, timeout(5000)).send(eq(EMAIL), eq(SUBJECT), anyString());
	}

	@Test
	void askingForALinkNeedsTheCsrfToken() throws Exception {
		mvc.perform(post("/password/forgot").param("email", EMAIL)).andExpect(redirectedUrl("/login?expired"));
		verify(mail, after(300).never()).send(anyString(), anyString(), anyString());
	}

	// ------------------------------------------------------------------ using the link

	@Test
	void theLinkSetsANewPasswordOnceAndTheOldOneStopsWorking() throws Exception {
		requestLink(EMAIL);
		String token = token(sentBody());

		String form = mvc.perform(get("/password/reset").param("token", token))
			.andExpect(status().isOk())
			.andExpect(view().name("auth/set-password"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(form).contains("<title>Choose a new password | Kosh</title>")
			.contains("Hello Meera Deshpande. Choose a new")
			.contains("<strong>meera.deshpande@reset.kosh.test</strong>")
			.containsPattern("<form class=\"auth-form\" method=\"post\" action=\"/password/reset\" novalidate>")
			.contains("<input type=\"hidden\" name=\"token\" value=\"" + token + "\">")
			.contains("autocomplete=\"new-password\"")
			.contains(">Save new password</button>")
			.contains("Didn’t ask for this? Ignore this page: your password stays the same.");

		MvcResult done = reset(token, NEW_PASSWORD, NEW_PASSWORD).andExpect(redirectedUrl("/login")).andReturn();
		assertThat(passwordEncoder.matches(NEW_PASSWORD, meera().getPasswordHash())).isTrue();
		assertThat(tokens.findByTokenHashAndPurpose(hasher.hash(token), Purpose.RESET).orElseThrow().getUsedAt())
			.isEqualTo(NOW);
		String login = mvc.perform(get("/login").flashAttrs(done.getFlashMap()))
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(login).contains("Your password is changed. Sign in with your new password.")
			.contains("value=\"meera.deshpande@reset.kosh.test\"");

		signIn(PASSWORD).andExpect(redirectedUrl("/login")).andExpect(unauthenticated());
		signIn(NEW_PASSWORD).andExpect(redirectedUrl("/hr")).andExpect(authenticated().withUsername(EMAIL));
		List<AuditEvent> trail = events.findAll(AuditEventRepository.matching(
				new AuditFilter(null, null, User.AUDIT_TYPE, meera.getId(), null, null), null),
				AuditEventRepository.NEWEST_FIRST).reversed();
		assertThat(trail).extracting(AuditEvent::getAction)
			.containsExactly(AuditAction.PASSWORD_RESET, AuditAction.LOGIN_FAILED, AuditAction.LOGIN_SUCCEEDED);
		// Nobody was signed in: the link's owner is the actor.
		assertThat(trail.getFirst().getActorEmail()).isEqualTo(EMAIL);
		assertThat(trail.getFirst().getSummary())
			.isEqualTo("Chose a new password from a reset link; open sessions were signed out");

		// Single use.
		String used = mvc.perform(get("/password/reset").param("token", token))
			.andExpect(view().name("auth/link-invalid"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(used).contains("This link no longer works")
			.contains("Ask for a new link and use the newest email.")
			.contains("<a class=\"btn btn-primary auth-submit\" href=\"/password/forgot\">Get a new link</a>")
			.doesNotContain("send you a new invite");
		reset(token, "Another pass 2026", "Another pass 2026").andExpect(view().name("auth/link-invalid"));
		assertThat(passwordEncoder.matches(NEW_PASSWORD, meera().getPasswordHash())).isTrue();
	}

	@Test
	void theNewPasswordFollowsTheSameRulesAsAnInvite() throws Exception {
		requestLink(EMAIL);
		String token = token(sentBody());

		assertThat(resetPage(token, NEW_PASSWORD, "Rankala Lake 2025")).contains("The passwords don’t match.")
			.doesNotContain(NEW_PASSWORD);
		assertThat(resetPage(token, "short1", "short1")).contains("Use 8 to 64 characters.")
			.contains("aria-describedby=\"password-help password-error\"");
		assertThat(resetPage(token, "", "")).contains("Enter a password.");
		assertThat(passwordEncoder.matches(PASSWORD, meera().getPasswordHash())).isTrue();
		mvc.perform(get("/password/reset").param("token", token)).andExpect(view().name("auth/set-password"));
	}

	@Test
	void anExpiredUnknownOrDisabledLinkDoesNotWork() throws Exception {
		tokens.save(new UserToken(meera, Purpose.RESET, hasher.hash("expires-now"), NOW));
		tokens.save(new UserToken(meera, Purpose.RESET, hasher.hash("one-more-minute"), NOW.plusMinutes(1)));

		mvc.perform(get("/password/reset").param("token", "expires-now")).andExpect(view().name("auth/link-invalid"));
		mvc.perform(get("/password/reset").param("token", "not-a-token")).andExpect(view().name("auth/link-invalid"));
		mvc.perform(get("/password/reset")).andExpect(view().name("auth/link-invalid"));
		mvc.perform(get("/password/reset").param("token", "one-more-minute")).andExpect(view().name("auth/set-password"));

		meera.setEnabled(false);
		users.save(meera);
		mvc.perform(get("/password/reset").param("token", "one-more-minute")).andExpect(view().name("auth/link-invalid"));
		reset("one-more-minute", NEW_PASSWORD, NEW_PASSWORD).andExpect(view().name("auth/link-invalid"));
		assertThat(passwordEncoder.matches(PASSWORD, meera().getPasswordHash())).isTrue();
	}

	@Test
	void resetAndInviteLinksOnlyWorkOnTheirOwnPage() throws Exception {
		tokens.save(new UserToken(meera, Purpose.RESET, hasher.hash("a-reset-link"), NOW.plusMinutes(30)));
		User rohan = users.save(new User("rohan.kale@reset.kosh.test", "Rohan Kale", Set.of(Role.EMPLOYEE)));
		tokens.save(new UserToken(rohan, Purpose.INVITE, hasher.hash("an-invite-link"), NOW.plusHours(48)));

		mvc.perform(get("/password/set").param("token", "a-reset-link")).andExpect(view().name("auth/link-invalid"));
		mvc.perform(get("/password/reset").param("token", "an-invite-link")).andExpect(view().name("auth/link-invalid"));
		mvc.perform(get("/password/set").param("token", "an-invite-link")).andExpect(view().name("auth/set-password"));
	}

	@Test
	void anInvitedUserWhoseInviteRanOutCanChooseAPasswordThisWay() throws Exception {
		User rohan = users.save(new User("rohan.kale@reset.kosh.test", "Rohan Kale", Set.of(Role.EMPLOYEE)));

		requestLink(rohan.getEmail());
		ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
		verify(mail, timeout(5000)).send(eq(rohan.getEmail()), eq(SUBJECT), body.capture());
		reset(token(body.getValue()), NEW_PASSWORD, NEW_PASSWORD).andExpect(redirectedUrl("/login"));

		assertThat(passwordEncoder.matches(NEW_PASSWORD, users.findById(rohan.getId()).orElseThrow().getPasswordHash()))
			.isTrue();
	}

	@Test
	void aNewPasswordFromAnyLinkSpendsEveryOpenLinkOfTheAccount() throws Exception {
		User rohan = users.save(new User("rohan.kale@reset.kosh.test", "Rohan Kale", Set.of(Role.EMPLOYEE)));
		tokens.save(new UserToken(rohan, Purpose.INVITE, hasher.hash("an-invite-link"), NOW.plusHours(48)));
		tokens.save(new UserToken(rohan, Purpose.RESET, hasher.hash("a-reset-link"), NOW.plusMinutes(30)));

		mvc.perform(post("/password/set").param("token", "an-invite-link")
			.param("password", NEW_PASSWORD)
			.param("confirmPassword", NEW_PASSWORD)
			.with(csrf())).andExpect(redirectedUrl("/login"));

		mvc.perform(get("/password/reset").param("token", "a-reset-link")).andExpect(view().name("auth/link-invalid"));
		assertThat(tokens.findByTokenHashAndPurpose(hasher.hash("a-reset-link"), Purpose.RESET).orElseThrow().getUsedAt())
			.isEqualTo(NOW);
	}

	@Test
	void aResetLiftsALockAndForgetsWrongPasswords() throws Exception {
		for (int failure = 0; failure < User.MAX_FAILED_LOGINS; failure++) {
			signIn("Wrong pass 2026");
		}
		assertThat(meera().isLockedAt(NOW)).isTrue();
		signIn(PASSWORD).andExpect(unauthenticated());

		requestLink(EMAIL);
		reset(token(sentBody()), NEW_PASSWORD, NEW_PASSWORD).andExpect(redirectedUrl("/login"));

		assertThat(meera().isLockedAt(NOW)).isFalse();
		assertThat(meera().getFailedLoginCount()).isZero();
		signIn(NEW_PASSWORD).andExpect(authenticated().withUsername(EMAIL));
	}

	@Test
	void aResetSignsTheAccountOutEverywhere() throws Exception {
		MockHttpSession open = (MockHttpSession) signIn(PASSWORD).andReturn().getRequest().getSession(false);
		mvc.perform(get("/hr").session(open)).andExpect(status().isOk());

		requestLink(EMAIL);
		reset(token(sentBody()), NEW_PASSWORD, NEW_PASSWORD).andExpect(redirectedUrl("/login"));

		mvc.perform(get("/hr").session(open)).andExpect(redirectedUrl("/login?ended"));
	}

	@Test
	void settingANewPasswordNeedsTheCsrfToken() throws Exception {
		tokens.save(new UserToken(meera, Purpose.RESET, hasher.hash("a-reset-link"), NOW.plusMinutes(30)));

		mvc.perform(post("/password/reset").param("token", "a-reset-link")
			.param("password", NEW_PASSWORD)
			.param("confirmPassword", NEW_PASSWORD)).andExpect(redirectedUrl("/login?expired"));
		assertThat(passwordEncoder.matches(PASSWORD, meera().getPasswordHash())).isTrue();
	}

	// ------------------------------------------------------------------ helpers

	/** Meera as committed now. */
	private User meera() {
		return users.findByEmailIgnoreCase(EMAIL).orElseThrow();
	}

	private MvcResult requestLink(String email) throws Exception {
		return mvc.perform(post("/password/forgot").param("email", email).with(csrf()))
			.andExpect(redirectedUrl("/password/forgot?sent"))
			.andReturn();
	}

	/** The page the request redirects to, with its one-time flash. */
	private String sentPage(MvcResult request) throws Exception {
		return mvc.perform(get("/password/forgot").param("sent", "").flashAttrs(request.getFlashMap()))
			.andExpect(status().isOk())
			.andExpect(view().name("auth/reset-sent"))
			.andReturn()
			.getResponse()
			.getContentAsString();
	}

	private ResultActions reset(String token, String password, String confirm)
			throws Exception {
		return mvc.perform(post("/password/reset").param("token", token)
			.param("password", password)
			.param("confirmPassword", confirm)
			.with(csrf()));
	}

	private String resetPage(String token, String password, String confirm) throws Exception {
		return reset(token, password, confirm).andExpect(status().isOk())
			.andExpect(view().name("auth/set-password"))
			.andReturn()
			.getResponse()
			.getContentAsString();
	}

	private ResultActions signIn(String password) throws Exception {
		return mvc.perform(post("/login").param("email", EMAIL).param("password", password).with(csrf()));
	}

	private String page(String path) throws Exception {
		return mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
	}

	/** Waits for the reset e-mail to Meera (sent on a background thread) and returns its body. */
	private String sentBody() {
		ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
		verify(mail, timeout(5000)).send(eq(EMAIL), eq(SUBJECT), body.capture());
		return body.getValue();
	}

	private static String token(String body) {
		Matcher link = LINK.matcher(body);
		assertThat(link.find()).as("reset link in %s", body).isTrue();
		return link.group(1);
	}

}
