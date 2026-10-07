package com.kosh.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.mail.MailSendException;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import com.kosh.common.BusinessRuleException;
import com.kosh.common.HmacHasher;
import com.kosh.notification.MailService;
import com.kosh.security.KoshUserDetails;
import com.kosh.user.UserToken.Purpose;
import com.kosh.web.Flash;

/**
 * User administration through the real pages (PLAN T08): list, search and role filter, invites with a single-use
 * set-password link, role changes, enable/disable, the rules that keep an active admin, sessions ending, and who may
 * do any of it. Mail is a spy on the log transport. Every test runs in a rolled-back transaction.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@MockitoSpyBean(types = MailService.class)
class UserAdminTest {

	/** The fixed test clock. */
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 5, 10, 0);

	private static final String DOMAIN = "@admin.kosh.test";

	private static final String PASSWORD = "Demo@1234";

	private static final Pattern LINK = Pattern.compile("http://localhost:8080/password/set\\?token=([A-Za-z0-9_-]{43})");

	private final MockMvc mvc;

	private final UserRepository users;

	private final UserTokenRepository tokens;

	private final UserAdminService service;

	private final MailService mail;

	private final HmacHasher hasher;

	private final PasswordEncoder passwordEncoder;

	private String passwordHash;

	private User admin;

	@Autowired
	UserAdminTest(MockMvc mvc, UserRepository users, UserTokenRepository tokens, UserAdminService service,
			MailService mail, HmacHasher hasher, PasswordEncoder passwordEncoder) {
		this.mvc = mvc;
		this.users = users;
		this.tokens = tokens;
		this.service = service;
		this.mail = mail;
		this.hasher = hasher;
		this.passwordEncoder = passwordEncoder;
	}

	@BeforeEach
	void signedInAdmin() {
		passwordHash = passwordEncoder.encode(PASSWORD);
		admin = save("asha.patil" + DOMAIN, "Asha Patil", Role.ADMIN, Role.EMPLOYEE);
	}

	// ------------------------------------------------------------------ list

	@Test
	void listShowsEachUserWithRolesStatusAndLastSignIn() throws Exception {
		User meera = save("meera.deshpande" + DOMAIN, "Meera Deshpande", Role.HR, Role.EMPLOYEE);
		meera.recordLoginSuccess(NOW.minusHours(2));
		users.save(new User("rohan.kale" + DOMAIN, "Rohan Kale", Set.of(Role.PAYROLL, Role.EMPLOYEE)));
		save("vikram.shinde" + DOMAIN, "Vikram Shinde", Role.FINANCE, Role.EMPLOYEE).setEnabled(false);
		save("sunita.more" + DOMAIN, "Sunita More", Role.EMPLOYEE).lockUntil(NOW.plusMinutes(10));

		String page = page(get("/admin/users").param("q", DOMAIN), "admin/users/list");

		assertThat(page).contains("<title>Users and roles | Kosh</title>")
			.contains("<h1 class=\"page-title\">Users and roles</h1>")
			.contains("<a class=\"nav-link\" href=\"/admin/users\" title=\"Users and roles\" aria-current=\"page\">")
			.contains("<a class=\"btn btn-primary\" href=\"/admin/users/invite\">")
			.contains("<caption class=\"visually-hidden\">Users</caption>")
			.containsPattern("<a class=\"cell-title\" href=\"/admin/users/" + meera.getId() + "\">Meera Deshpande</a>\\s*"
					+ "<span class=\"cell-sub\">meera.deshpande@admin.kosh.test</span>")
			.containsPattern("<li class=\"badge\">HR</li>\\s*<li class=\"badge\">Employee</li>")
			.contains("<td>2 hours ago</td>")
			.contains("<span class=\"badge\" data-status=\"ACTIVE\">Active</span>")
			.contains("<span class=\"badge\" data-status=\"INVITED\">Invited</span>")
			.contains("<span class=\"badge\" data-status=\"DISABLED\">Disabled</span>")
			.contains("<span class=\"badge\" data-status=\"LOCKED\">Locked</span>")
			.contains("<td>Never</td>")
			.contains("Showing <strong>1–5</strong>")
			.doesNotContain("{bcrypt}");
		// Sorted by name: Asha, Meera, Rohan, Sunita, Vikram.
		assertThat(page.indexOf(">Asha Patil</a>")).isPositive().isLessThan(page.indexOf("Meera Deshpande"));
		assertThat(page.indexOf("Meera Deshpande")).isLessThan(page.indexOf("Rohan Kale"));
		assertThat(page.indexOf("Sunita More")).isLessThan(page.indexOf("Vikram Shinde"));
	}

	@Test
	void searchMatchesNameOrEmailInAnyCaseAndTheRoleFilterNarrowsIt() throws Exception {
		save("meera.deshpande" + DOMAIN, "Meera Deshpande", Role.HR, Role.EMPLOYEE);
		save("nikhil.pawar" + DOMAIN, "Nikhil Pawar", Role.FINANCE, Role.EMPLOYEE);

		assertThat(page(get("/admin/users").param("q", "  DESHPANDE "), "admin/users/list")).contains("Meera Deshpande")
			.doesNotContain("Nikhil Pawar")
			.contains("value=\"DESHPANDE\"");
		assertThat(page(get("/admin/users").param("q", "nikhil.pawar@"), "admin/users/list")).contains("Nikhil Pawar")
			.doesNotContain("Meera Deshpande");
		String finance = page(get("/admin/users").param("q", DOMAIN).param("role", "FINANCE"), "admin/users/list");
		assertThat(finance).contains("Nikhil Pawar")
			.doesNotContain("Meera Deshpande")
			// Asha, the signed-in admin, is in the user menu but not in the table.
			.doesNotContain(">Asha Patil</a>")
			.contains("<option value=\"FINANCE\" selected=\"selected\">Finance</option>")
			.contains("<a class=\"btn btn-ghost\" href=\"/admin/users\">Clear</a>");
		// Anything that is not a role means all roles.
		assertThat(page(get("/admin/users").param("q", DOMAIN).param("role", "ROOT"), "admin/users/list"))
			.contains("Nikhil Pawar")
			.contains("Meera Deshpande");
		// LIKE wildcards are plain text.
		assertThat(page(get("/admin/users").param("q", "%"), "admin/users/list")).doesNotContain("Meera Deshpande")
			.contains("No users match these filters")
			.contains("<a class=\"btn btn-secondary\" href=\"/admin/users\">Clear filters</a>");
	}

	@Test
	void sortingWorksOnListedColumnsOnlyAndPagesHoldTwentyFive() throws Exception {
		IntStream.rangeClosed(1, 26)
			.forEach(n -> save("bulk%02d".formatted(n) + DOMAIN, "Bulk User %02d".formatted(n), Role.EMPLOYEE));

		String byEmailDesc = page(get("/admin/users").param("q", "bulk").param("sort", "email,desc"), "admin/users/list");
		assertThat(byEmailDesc).contains("Bulk User 26").doesNotContain("Bulk User 01").contains("Showing <strong>1–25</strong>");
		// An unknown sort property (a password hash!) is ignored: back to name order.
		String hashed = page(get("/admin/users").param("q", "bulk").param("sort", "passwordHash,asc"), "admin/users/list");
		assertThat(hashed).contains("Bulk User 01").doesNotContain("Bulk User 26");
		assertThat(page(get("/admin/users").param("q", "bulk").param("page", "1"), "admin/users/list"))
			.contains("Bulk User 26")
			.contains("Showing <strong>26–26</strong>");
	}

	// ------------------------------------------------------------------ access

	@ParameterizedTest
	@ValueSource(strings = { "HR", "PAYROLL", "FINANCE", "MANAGER", "EMPLOYEE" })
	void onlyAdminsReachUserAdministration(String role) throws Exception {
		User target = save("target" + DOMAIN, "Target User", Role.EMPLOYEE);
		RequestPostProcessor other = user("someone" + DOMAIN).roles(role, "EMPLOYEE");
		for (String path : List.of("/admin/users", "/admin/users/invite", "/admin/users/" + target.getId())) {
			mvc.perform(get(path).with(other)).andExpect(status().isForbidden());
		}
		for (String path : List.of("/admin/users/invite", "/admin/users/" + target.getId(),
				"/admin/users/" + target.getId() + "/disable", "/admin/users/" + target.getId() + "/enable",
				"/admin/users/" + target.getId() + "/invite")) {
			mvc.perform(post(path).param("displayName", "X")
				.param("email", "x" + DOMAIN)
				.param("roles", "ADMIN")
				.with(other)
				.with(csrf())).andExpect(status().isForbidden());
		}
		assertThat(target.getRoles()).containsExactly(Role.EMPLOYEE);
		assertThat(target.isEnabled()).isTrue();
		verify(mail, never()).send(anyString(), anyString(), anyString());
	}

	@Test
	void anonymousVisitorsAreSentToSignIn() throws Exception {
		mvc.perform(get("/admin/users")).andExpect(redirectedUrl("/login"));
		mvc.perform(post("/admin/users/invite").with(csrf())).andExpect(redirectedUrl("/login"));
	}

	@Test
	@WithMockUser(username = "hr" + DOMAIN, roles = { "HR", "EMPLOYEE" })
	void theServiceChecksTheRoleAgain() {
		assertThatThrownBy(() -> service.list("", null, PageRequest.of(0, 25)))
			.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> service.enable(admin.getId())).isInstanceOf(AccessDeniedException.class);
	}

	@Test
	void formsNeedTheCsrfToken() throws Exception {
		User target = save("target" + DOMAIN, "Target User", Role.EMPLOYEE);
		mvc.perform(post("/admin/users/" + target.getId() + "/disable").with(asAdmin()))
			.andExpect(redirectedUrl("/login?expired"));
		assertThat(target.isEnabled()).isTrue();
	}

	@Test
	void unknownUserIsNotFound() throws Exception {
		mvc.perform(get("/admin/users/999999").with(asAdmin()))
			.andExpect(status().isNotFound())
			.andExpect(view().name("error/404"));
	}

	// ------------------------------------------------------------------ invite

	@Test
	void inviteCreatesAnAccountWithoutAPasswordAndEmailsASingleUseLink() throws Exception {
		String invitePage = page(get("/admin/users/invite"), "admin/users/invite");
		assertThat(invitePage).contains("<title>Invite user | Kosh</title>")
			.contains("<li><a href=\"/admin/users\">Users and roles</a></li>")
			.contains("<label class=\"label\" for=\"displayName\">")
			.contains("<label class=\"label\" for=\"email\">")
			.contains("<legend>Roles</legend>")
			.contains("<input class=\"checkbox\" type=\"checkbox\" value=\"EMPLOYEE\" aria-describedby=\"role-help-EMPLOYEE\" "
					+ "id=\"roles6\" name=\"roles\" checked=\"checked\">")
			.contains("<input class=\"checkbox\" type=\"checkbox\" value=\"HR\" aria-describedby=\"role-help-HR\" id=\"roles2\" "
					+ "name=\"roles\">")
			.contains("<span class=\"help\" id=\"role-help-MANAGER\">Approves leave, attendance and claims for their own team. Never sees salaries.</span>")
			.contains("Send invite</button>");

		MvcResult result = mvc.perform(post("/admin/users/invite").param("displayName", "  Meera Deshpande ")
			.param("email", " Meera.Deshpande@Admin.Kosh.Test ")
			.param("roles", "HR", "EMPLOYEE")
			.with(asAdmin())
			.with(csrf())).andExpect(status().isFound()).andReturn();

		User meera = users.findByEmailIgnoreCase("meera.deshpande" + DOMAIN).orElseThrow();
		assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/admin/users/" + meera.getId());
		assertThat(meera.getDisplayName()).isEqualTo("Meera Deshpande");
		assertThat(meera.getRoles()).containsExactlyInAnyOrder(Role.HR, Role.EMPLOYEE);
		assertThat(meera.getPasswordHash()).isNull();
		assertThat(meera.isEnabled()).isTrue();

		String body = sentBody("meera.deshpande" + DOMAIN, "You're invited to Kosh");
		assertThat(body).contains("Hello Meera Deshpande,")
			.contains("Asha Patil has set up a Kosh account for you.")
			.contains("Sign-in email: meera.deshpande@admin.kosh.test")
			.contains("Roles: HR, Employee")
			.contains("The link works once and expires on 7 Oct 2026, 10:00 AM IST.");
		String token = token(body);
		UserToken stored = tokens.findByTokenHashAndPurpose(hasher.hash(token), Purpose.INVITE).orElseThrow();
		assertThat(stored.getUser()).isEqualTo(meera);
		assertThat(stored.getExpiresAt()).isEqualTo(NOW.plusHours(48));
		assertThat(stored.getUsedAt()).isNull();
		// Only the keyed hash is stored, never the token from the e-mail.
		assertThat(tokens.findByTokenHashAndPurpose(token, Purpose.INVITE)).isEmpty();

		Flash flash = (Flash) result.getFlashMap().get(Flash.ATTRIBUTE);
		assertThat(flash.title()).isEqualTo("Invite sent to meera.deshpande@admin.kosh.test");
		String detail = page(get("/admin/users/" + meera.getId()), "admin/users/edit");
		assertThat(detail).contains("<span class=\"badge\" data-status=\"INVITED\">Invited</span>")
			.contains("Invite sent just now. The link works until 7 Oct 2026, 10:00 AM.")
			.contains("Send a new invite</button>");
	}

	@Test
	void inviteFormShowsEveryProblemAtOnceAndKeepsWhatWasTyped() throws Exception {
		String blank = mvc.perform(post("/admin/users/invite").param("displayName", " ")
			.param("email", "")
			.with(asAdmin())
			.with(csrf()))
			.andExpect(status().isOk())
			.andExpect(view().name("admin/users/invite"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(blank).contains("Enter the person’s full name.")
			.contains("Enter their work email.")
			.contains("Choose at least one role.")
			.contains("aria-describedby=\"roles-error\"");

		String wrong = invite("Meera Deshpande", "meera at panchganga", "HR");
		assertThat(wrong).contains("Enter an email like name@company.in.")
			.contains(UserAdminService.NEEDS_EMPLOYEE)
			.contains("value=\"Meera Deshpande\"")
			.contains("value=\"meera at panchganga\"");

		String taken = invite("Asha P", "ASHA.PATIL" + DOMAIN.toUpperCase(), "EMPLOYEE");
		assertThat(taken).contains("A user with this email already exists.");

		assertThat(users.findByEmailIgnoreCase("meera at panchganga")).isEmpty();
		verify(mail, never()).send(anyString(), anyString(), anyString());
	}

	@Test
	void anAdminOnlyAccountNeedsNoEmployeeRole() throws Exception {
		mvc.perform(post("/admin/users/invite").param("displayName", "IT Desk")
			.param("email", "it.desk" + DOMAIN)
			.param("roles", "ADMIN")
			.with(asAdmin())
			.with(csrf())).andExpect(status().isFound());
		assertThat(users.findByEmailIgnoreCase("it.desk" + DOMAIN).orElseThrow().getRoles()).containsExactly(Role.ADMIN);
	}

	@Test
	void anInviteThatCannotBeMailedAddsNobody() throws Exception {
		doThrow(new MailSendException("Connection refused")).when(mail).send(anyString(), anyString(), anyString());

		String page = invite("Meera Deshpande", "meera.deshpande" + DOMAIN, "HR", "EMPLOYEE");

		assertThat(page).contains("<div class=\"flash flash-danger\" role=\"alert\">")
			.contains("We couldn’t send the invite email, so nobody was added.")
			.contains("value=\"meera.deshpande@admin.kosh.test\"");
		// The service's transaction failed: in production it rolls back the new user (here the test's does).
		assertThat(TestTransaction.isFlaggedForRollback()).isTrue();
	}

	@Test
	void resendingAnInviteReplacesTheEarlierLink() throws Exception {
		User meera = invited("meera.deshpande" + DOMAIN, "Meera Deshpande");
		String first = token(sentBody(meera.getEmail(), "You're invited to Kosh"));

		MvcResult result = mvc.perform(post("/admin/users/" + meera.getId() + "/invite").with(asAdmin()).with(csrf()))
			.andExpect(redirectedUrl("/admin/users/" + meera.getId()))
			.andReturn();
		assertThat(((Flash) result.getFlashMap().get(Flash.ATTRIBUTE)).title())
			.isEqualTo("New invite sent to meera.deshpande@admin.kosh.test");

		ArgumentCaptor<String> bodies = ArgumentCaptor.forClass(String.class);
		verify(mail, times(2)).send(eq(meera.getEmail()), anyString(), bodies.capture());
		String second = token(bodies.getAllValues().get(1));
		assertThat(second).isNotEqualTo(first);
		mvc.perform(get("/password/set").param("token", first)).andExpect(view().name("auth/link-invalid"));
		mvc.perform(get("/password/set").param("token", second)).andExpect(view().name("auth/set-password"));
	}

	@Test
	void aResentInviteThatCannotBeMailedKeepsTheEarlierLink() throws Exception {
		User meera = invited("meera.deshpande" + DOMAIN, "Meera Deshpande");
		doThrow(new MailSendException("Connection refused")).when(mail).send(anyString(), anyString(), anyString());

		mvc.perform(post("/admin/users/" + meera.getId() + "/invite").with(asAdmin()).with(csrf()))
			.andExpect(redirectedUrl("/admin/users/" + meera.getId()))
			.andExpect(flash().attribute(Flash.ATTRIBUTE, Flash.danger("We couldn’t send the invite email.")
				.withText("The earlier link still works. Check the mail server settings and try again.")));
		// The service's transaction failed: in production it rolls back spending the earlier link (here the test's does).
		assertThat(TestTransaction.isFlaggedForRollback()).isTrue();
	}

	@Test
	void aConcurrentInviteForTheSameEmailIsAnsweredOnTheField() {
		// Another invite inserted the e-mail between the duplicate check and this insert.
		UserRepository racing = mock(UserRepository.class);
		when(racing.save(any(User.class))).thenThrow(new DataIntegrityViolationException("uk_users_email"));
		UserAdminService unproxied = new UserAdminService(racing, null, null, null, null, null, null, null);

		assertThatThrownBy(() -> unproxied.invite("Meera Deshpande", "meera.deshpande" + DOMAIN,
				Set.of(Role.HR, Role.EMPLOYEE), null))
			.isInstanceOf(BusinessRuleException.class)
			.hasMessage("A user with this email already exists.")
			.extracting("field")
			.isEqualTo("email");
	}

	@Test
	void anInviteIsOnlyResentToAnEnabledUserWithoutAPassword() throws Exception {
		User active = save("rohan.kale" + DOMAIN, "Rohan Kale", Role.PAYROLL, Role.EMPLOYEE);
		mvc.perform(post("/admin/users/" + active.getId() + "/invite").with(asAdmin()).with(csrf()))
			.andExpect(flash().attribute(Flash.ATTRIBUTE, Flash.danger("Rohan Kale has already set a password.")));

		User off = invited("sunita.more" + DOMAIN, "Sunita More");
		off.setEnabled(false);
		mvc.perform(post("/admin/users/" + off.getId() + "/invite").with(asAdmin()).with(csrf()))
			.andExpect(flash().attribute(Flash.ATTRIBUTE, Flash.danger("Enable the account before sending a new invite.")));
		verify(mail, times(1)).send(anyString(), anyString(), anyString());
	}

	// ------------------------------------------------------------------ set password

	@Test
	void theInviteLinkSetsAPasswordOnceAndTheUserCanSignIn() throws Exception {
		User meera = invited("meera.deshpande" + DOMAIN, "Meera Deshpande");
		String token = token(sentBody(meera.getEmail(), "You're invited to Kosh"));

		String form = mvc.perform(get("/password/set").param("token", token))
			.andExpect(status().isOk())
			.andExpect(view().name("auth/set-password"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(form).contains("<title>Set your password | Kosh</title>")
			.contains("Hello Meera Deshpande.")
			.contains("<strong>meera.deshpande@admin.kosh.test</strong>")
			.contains("<input type=\"hidden\" name=\"token\" value=\"" + token + "\">")
			.containsPattern("<input type=\"hidden\" name=\"_csrf\" value=\"[^\"]+\"/>")
			.contains("autocomplete=\"new-password\"")
			.contains("8 to 64 characters.");

		MvcResult done = mvc.perform(post("/password/set").param("token", token)
			.param("password", "Kolhapur@2026")
			.param("confirmPassword", "Kolhapur@2026")
			.with(csrf())).andExpect(redirectedUrl("/login")).andReturn();

		assertThat(passwordEncoder.matches("Kolhapur@2026", meera.getPasswordHash())).isTrue();
		assertThat(tokens.findByTokenHashAndPurpose(hasher.hash(token), Purpose.INVITE).orElseThrow().getUsedAt())
			.isEqualTo(NOW);
		String login = mvc.perform(get("/login").flashAttrs(done.getFlashMap()))
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(login).contains("Your password is set. Sign in to continue.")
			.contains("value=\"meera.deshpande@admin.kosh.test\"");
		mvc.perform(post("/login").param("email", meera.getEmail()).param("password", "Kolhapur@2026").with(csrf()))
			.andExpect(redirectedUrl("/hr"));

		// Single use.
		mvc.perform(get("/password/set").param("token", token)).andExpect(view().name("auth/link-invalid"));
		mvc.perform(post("/password/set").param("token", token)
			.param("password", "Another@2026")
			.param("confirmPassword", "Another@2026")
			.with(csrf())).andExpect(view().name("auth/link-invalid"));
		assertThat(passwordEncoder.matches("Kolhapur@2026", meera.getPasswordHash())).isTrue();
	}

	@Test
	void theNewPasswordMustFollowTheRulesAndMatch() throws Exception {
		User meera = invited("meera.deshpande" + DOMAIN, "Meera Deshpande");
		String token = token(sentBody(meera.getEmail(), "You're invited to Kosh"));

		assertThat(setPassword(token, "Kolhapur@2026", "Kolhapur@2025")).contains("The passwords don’t match.")
			.contains("aria-describedby=\"confirmPassword-error\"")
			.doesNotContain("Kolhapur@2026");
		assertThat(setPassword(token, "short1", "short1")).contains("Use 8 to 64 characters.")
			.contains("aria-describedby=\"password-help password-error\"");
		assertThat(setPassword(token, "x".repeat(65), "x".repeat(65))).contains("Use 8 to 64 characters.");
		assertThat(setPassword(token, "€".repeat(25), "€".repeat(25)))
			.contains("That password is too long for these characters. Use a shorter one.");
		assertThat(setPassword(token, "", "")).contains("Enter a password.");
		assertThat(meera.getPasswordHash()).isNull();
		mvc.perform(get("/password/set").param("token", token)).andExpect(view().name("auth/set-password"));
	}

	@Test
	void expiredUnknownOrDisabledLinksDoNotWork() throws Exception {
		User meera = invited("meera.deshpande" + DOMAIN, "Meera Deshpande");
		tokens.save(new UserToken(meera, Purpose.INVITE, hasher.hash("expires-now"), NOW));
		tokens.save(new UserToken(meera, Purpose.INVITE, hasher.hash("one-more-minute"), NOW.plusMinutes(1)));

		String expired = mvc.perform(get("/password/set").param("token", "expires-now"))
			.andExpect(status().isOk())
			.andExpect(view().name("auth/link-invalid"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(expired).contains("<title>Link no longer works | Kosh</title>")
			.contains("This link no longer works")
			.contains("Ask your Kosh administrator to send you a new invite.")
			.doesNotContain("meera");
		mvc.perform(get("/password/set").param("token", "one-more-minute")).andExpect(view().name("auth/set-password"));
		mvc.perform(get("/password/set").param("token", "not-a-token")).andExpect(view().name("auth/link-invalid"));
		mvc.perform(get("/password/set")).andExpect(view().name("auth/link-invalid"));

		meera.setEnabled(false);
		mvc.perform(get("/password/set").param("token", "one-more-minute")).andExpect(view().name("auth/link-invalid"));
		mvc.perform(post("/password/set").param("token", "one-more-minute")
			.param("password", "Kolhapur@2026")
			.param("confirmPassword", "Kolhapur@2026")
			.with(csrf())).andExpect(view().name("auth/link-invalid"));
		assertThat(meera.getPasswordHash()).isNull();
	}

	@Test
	void settingAPasswordNeedsTheCsrfToken() throws Exception {
		User meera = invited("meera.deshpande" + DOMAIN, "Meera Deshpande");
		String token = token(sentBody(meera.getEmail(), "You're invited to Kosh"));
		mvc.perform(post("/password/set").param("token", token)
			.param("password", "Kolhapur@2026")
			.param("confirmPassword", "Kolhapur@2026")).andExpect(redirectedUrl("/login?expired"));
		assertThat(meera.getPasswordHash()).isNull();
	}

	// ------------------------------------------------------------------ edit roles

	@Test
	void editPageShowsTheAccountAndSavesNameAndRoles() throws Exception {
		User rohan = save("rohan.kale" + DOMAIN, "Rohan Kale", Role.PAYROLL, Role.EMPLOYEE);
		rohan.recordLoginSuccess(NOW.minusDays(1));

		String page = page(get("/admin/users/" + rohan.getId()), "admin/users/edit");
		assertThat(page).contains("<title>Rohan Kale | Kosh</title>")
			.contains("<li><a href=\"/admin/users\">Users and roles</a></li>")
			.contains("value=\"Rohan Kale\"")
			.containsPattern("value=\"PAYROLL\"[^>]+checked=\"checked\">")
			.doesNotContainPattern("value=\"FINANCE\"[^>]+checked")
			.contains("<span class=\"badge\" data-status=\"ACTIVE\">Active</span>")
			.contains("<dd>4 Oct 2026, 10:00 AM</dd>")
			.contains("Disable account</button>")
			.doesNotContain("Send a new invite");

		MvcResult result = mvc.perform(post("/admin/users/" + rohan.getId()).param("displayName", "Rohan S. Kale")
			.param("roles", "FINANCE", "EMPLOYEE")
			.with(asAdmin())
			.with(csrf())).andExpect(redirectedUrl("/admin/users/" + rohan.getId())).andReturn();

		assertThat(rohan.getDisplayName()).isEqualTo("Rohan S. Kale");
		assertThat(rohan.getRoles()).containsExactlyInAnyOrder(Role.FINANCE, Role.EMPLOYEE);
		assertThat(result.getFlashMap().get(Flash.ATTRIBUTE)).isEqualTo(Flash.success("Saved changes for Rohan S. Kale")
			.withText("Their open sessions have ended, so the new roles apply when they next sign in."));
	}

	@Test
	void roleRulesAreCheckedOnEdit() throws Exception {
		User rohan = save("rohan.kale" + DOMAIN, "Rohan Kale", Role.PAYROLL, Role.EMPLOYEE);

		assertThat(update(rohan, "Rohan Kale")).contains("Choose at least one role.");
		assertThat(update(rohan, "Rohan Kale", "MANAGER")).contains(UserAdminService.NEEDS_EMPLOYEE);
		assertThat(update(rohan, "", "PAYROLL", "EMPLOYEE")).contains("Enter the person’s full name.");
		assertThat(rohan.getRoles()).containsExactlyInAnyOrder(Role.PAYROLL, Role.EMPLOYEE);
	}

	@Test
	void anAdminCannotRemoveTheirOwnAdminRole() throws Exception {
		save("second.admin" + DOMAIN, "Second Admin", Role.ADMIN, Role.EMPLOYEE);

		assertThat(update(admin, "Asha Patil", "HR", "EMPLOYEE"))
			.contains("You can’t remove your own Admin role. Ask another admin to do it.");
		assertThat(admin.getRoles()).containsExactlyInAnyOrder(Role.ADMIN, Role.EMPLOYEE);

		// Adding a role to yourself is fine; it applies at the next sign-in.
		MvcResult result = mvc.perform(post("/admin/users/" + admin.getId()).param("displayName", "Asha Patil")
			.param("roles", "ADMIN", "HR", "EMPLOYEE")
			.with(asAdmin())
			.with(csrf())).andExpect(status().isFound()).andReturn();
		assertThat(admin.getRoles()).contains(Role.HR);
		assertThat(((Flash) result.getFlashMap().get(Flash.ATTRIBUTE)).text())
			.isEqualTo("Your new roles apply the next time you sign in.");
	}

	@Test
	void theLastActiveAdminKeepsTheRole() throws Exception {
		// Signed in as an admin who is not in the table (the rule must hold on its own).
		RequestPostProcessor outsider = user("outside.admin" + DOMAIN).roles("ADMIN");
		save("invited.admin" + DOMAIN, "Invited Admin", Role.ADMIN).setPasswordHash(null);
		save("disabled.admin" + DOMAIN, "Disabled Admin", Role.ADMIN).setEnabled(false);
		assertThat(users.countOtherActiveAdmins(admin.getId())).as("only Asha is active").isZero();

		String page = mvc.perform(post("/admin/users/" + admin.getId()).param("displayName", "Asha Patil")
			.param("roles", "EMPLOYEE")
			.with(outsider)
			.with(csrf())).andExpect(view().name("admin/users/edit")).andReturn().getResponse().getContentAsString();
		assertThat(page).contains(UserAdminService.LAST_ADMIN);
		mvc.perform(post("/admin/users/" + admin.getId() + "/disable").with(outsider).with(csrf()))
			.andExpect(flash().attribute(Flash.ATTRIBUTE, Flash.danger(UserAdminService.LAST_ADMIN)));
		assertThat(admin.getRoles()).contains(Role.ADMIN);
		assertThat(admin.isEnabled()).isTrue();

		// With a second active admin the change goes through.
		save("second.admin" + DOMAIN, "Second Admin", Role.ADMIN, Role.EMPLOYEE);
		mvc.perform(post("/admin/users/" + admin.getId()).param("displayName", "Asha Patil")
			.param("roles", "EMPLOYEE")
			.with(outsider)
			.with(csrf())).andExpect(status().isFound());
		assertThat(admin.getRoles()).containsExactly(Role.EMPLOYEE);
	}

	// ------------------------------------------------------------------ enable / disable

	@Test
	void disablingStopsSignInAndEnablingGivesItBack() throws Exception {
		User rohan = save("rohan.kale" + DOMAIN, "Rohan Kale", Role.PAYROLL, Role.EMPLOYEE);

		mvc.perform(post("/admin/users/" + rohan.getId() + "/disable").with(asAdmin()).with(csrf()))
			.andExpect(redirectedUrl("/admin/users/" + rohan.getId()))
			.andExpect(flash().attribute(Flash.ATTRIBUTE, Flash.success("Rohan Kale can no longer sign in")
				.withText("Their open sessions have ended. Enable the account to give access back.")));
		assertThat(rohan.isEnabled()).isFalse();
		assertThat(page(get("/admin/users/" + rohan.getId()), "admin/users/edit"))
			.contains("<span class=\"badge\" data-status=\"DISABLED\">Disabled</span>")
			.contains("Enable account</button>")
			.doesNotContain("Disable account</button>");
		mvc.perform(post("/login").param("email", rohan.getEmail()).param("password", PASSWORD).with(csrf()))
			.andExpect(redirectedUrl("/login"));

		mvc.perform(post("/admin/users/" + rohan.getId() + "/enable").with(asAdmin()).with(csrf()))
			.andExpect(flash().attribute(Flash.ATTRIBUTE, Flash.success("Rohan Kale can sign in again")));
		assertThat(rohan.isEnabled()).isTrue();
	}

	@Test
	void nobodyDisablesTheirOwnAccount() throws Exception {
		assertThat(page(get("/admin/users/" + admin.getId()), "admin/users/edit"))
			.contains("type=\"submit\" aria-describedby=\"disable-help\" disabled=\"disabled\">Disable account</button>")
			.contains("You can’t disable your own account.");

		mvc.perform(post("/admin/users/" + admin.getId() + "/disable").with(asAdmin()).with(csrf()))
			.andExpect(flash().attribute(Flash.ATTRIBUTE, Flash.danger("You can’t disable your own account.")));
		assertThat(admin.isEnabled()).isTrue();
	}

	// ------------------------------------------------------------------ sessions

	@Test
	void disablingAUserEndsTheSessionTheyAreSignedInWith() throws Exception {
		User rohan = save("rohan.kale" + DOMAIN, "Rohan Kale", Role.PAYROLL, Role.EMPLOYEE);
		MockHttpSession session = signIn(rohan);
		mvc.perform(get("/payroll").session(session)).andExpect(status().isOk());

		mvc.perform(post("/admin/users/" + rohan.getId() + "/disable").with(asAdmin()).with(csrf()))
			.andExpect(status().isFound());

		mvc.perform(get("/payroll").session(session)).andExpect(redirectedUrl("/login?ended"));
		String login = mvc.perform(get("/login").param("ended", "")).andReturn().getResponse().getContentAsString();
		assertThat(login).contains("Your account was changed, so you were signed out. Sign in again to continue.");
	}

	@Test
	void aSessionStartedFromARememberMeCookieEndsToo() throws Exception {
		User rohan = save("rohan.kale" + DOMAIN, "Rohan Kale", Role.PAYROLL, Role.EMPLOYEE);
		Cookie rememberMe = mvc
			.perform(post("/login").param("email", rohan.getEmail())
				.param("password", PASSWORD)
				.param("remember-me", "on")
				.with(csrf()))
			.andReturn()
			.getResponse()
			.getCookie("remember-me");
		MvcResult remembered = mvc.perform(get("/payroll").cookie(rememberMe)).andExpect(status().isOk()).andReturn();
		MockHttpSession session = (MockHttpSession) remembered.getRequest().getSession(false);

		mvc.perform(post("/admin/users/" + rohan.getId() + "/disable").with(asAdmin()).with(csrf()))
			.andExpect(status().isFound());

		mvc.perform(get("/payroll").session(session)).andExpect(redirectedUrl("/login?ended"));
		// The cookie no longer signs a disabled user in either.
		mvc.perform(get("/payroll").cookie(rememberMe)).andExpect(redirectedUrl("/login"));
	}

	@Test
	void changingRolesEndsTheUsersSessionsSoTheNewRolesApply() throws Exception {
		User rohan = save("rohan.kale" + DOMAIN, "Rohan Kale", Role.PAYROLL, Role.EMPLOYEE);
		MockHttpSession session = signIn(rohan);

		mvc.perform(post("/admin/users/" + rohan.getId()).param("displayName", "Rohan Kale")
			.param("roles", "EMPLOYEE")
			.with(asAdmin())
			.with(csrf())).andExpect(status().isFound());

		mvc.perform(get("/payroll").session(session)).andExpect(redirectedUrl("/login?ended"));
		MockHttpSession again = signIn(rohan);
		mvc.perform(get("/payroll").session(again)).andExpect(status().isForbidden());
		mvc.perform(get("/me").session(again)).andExpect(status().isOk());
	}

	@Test
	void savingWithoutARoleChangeKeepsSessions() throws Exception {
		User rohan = save("rohan.kale" + DOMAIN, "Rohan Kale", Role.PAYROLL, Role.EMPLOYEE);
		MockHttpSession session = signIn(rohan);

		mvc.perform(post("/admin/users/" + rohan.getId()).param("displayName", "Rohan S. Kale")
			.param("roles", "PAYROLL", "EMPLOYEE")
			.with(asAdmin())
			.with(csrf())).andExpect(flash().attribute(Flash.ATTRIBUTE, Flash.success("Saved changes for Rohan S. Kale")));

		mvc.perform(get("/payroll").session(session)).andExpect(status().isOk());
	}

	@Test
	void anEndedSessionAlsoCancelsTheRememberMeCookieSoTheUserSignsInAgain() throws Exception {
		User rohan = save("rohan.kale" + DOMAIN, "Rohan Kale", Role.PAYROLL, Role.EMPLOYEE);
		Cookie rememberMe = mvc
			.perform(post("/login").param("email", rohan.getEmail())
				.param("password", PASSWORD)
				.param("remember-me", "on")
				.with(csrf()))
			.andReturn()
			.getResponse()
			.getCookie("remember-me");
		MvcResult remembered = mvc.perform(get("/payroll").cookie(rememberMe)).andExpect(status().isOk()).andReturn();
		MockHttpSession session = (MockHttpSession) remembered.getRequest().getSession(false);

		// A role change keeps the cookie's signature valid, so only the cancel stops it signing the old browser back in.
		mvc.perform(post("/admin/users/" + rohan.getId()).param("displayName", "Rohan Kale")
			.param("roles", "EMPLOYEE")
			.with(asAdmin())
			.with(csrf())).andExpect(status().isFound());

		mvc.perform(get("/payroll").session(session).cookie(rememberMe))
			.andExpect(redirectedUrl("/login?ended"))
			.andExpect(cookie().maxAge("remember-me", 0));
		String login = mvc.perform(get("/login").param("ended", ""))
			.andExpect(view().name("auth/login"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(login).contains("Your account was changed, so you were signed out. Sign in again to continue.");
	}

	// ------------------------------------------------------------------ helpers

	private User save(String email, String displayName, Role... roles) {
		User user = new User(email, displayName, Set.of(roles));
		user.setPasswordHash(passwordHash);
		return users.save(user);
	}

	/** Invited through the page, so the e-mail with the link is on the spy. */
	private User invited(String email, String name) throws Exception {
		mvc.perform(post("/admin/users/invite").param("displayName", name)
			.param("email", email)
			.param("roles", "HR", "EMPLOYEE")
			.with(asAdmin())
			.with(csrf())).andExpect(status().isFound());
		return users.findByEmailIgnoreCase(email).orElseThrow();
	}

	private String invite(String name, String email, String... roles) throws Exception {
		return mvc.perform(post("/admin/users/invite").param("displayName", name)
			.param("email", email)
			.param("roles", roles)
			.with(asAdmin())
			.with(csrf()))
			.andExpect(status().isOk())
			.andExpect(view().name("admin/users/invite"))
			.andReturn()
			.getResponse()
			.getContentAsString();
	}

	private String update(User user, String name, String... roles) throws Exception {
		MockHttpServletRequestBuilder request = post("/admin/users/" + user.getId()).param("displayName", name);
		if (roles.length > 0) {
			request.param("roles", roles);
		}
		return mvc.perform(request.with(asAdmin()).with(csrf()))
			.andExpect(status().isOk())
			.andExpect(view().name("admin/users/edit"))
			.andReturn()
			.getResponse()
			.getContentAsString();
	}

	private String setPassword(String token, String password, String confirm) throws Exception {
		return mvc.perform(post("/password/set").param("token", token)
			.param("password", password)
			.param("confirmPassword", confirm)
			.with(csrf()))
			.andExpect(status().isOk())
			.andExpect(view().name("auth/set-password"))
			.andReturn()
			.getResponse()
			.getContentAsString();
	}

	private String page(MockHttpServletRequestBuilder request, String viewName) throws Exception {
		return mvc.perform(request.with(asAdmin()))
			.andExpect(status().isOk())
			.andExpect(view().name(viewName))
			.andReturn()
			.getResponse()
			.getContentAsString();
	}

	private MockHttpSession signIn(User user) throws Exception {
		MvcResult result = mvc
			.perform(post("/login").param("email", user.getEmail()).param("password", PASSWORD).with(csrf()))
			.andExpect(status().isFound())
			.andReturn();
		return (MockHttpSession) result.getRequest().getSession(false);
	}

	/** The signed-in admin as the real sign-in would hold them (display name included). */
	private RequestPostProcessor asAdmin() {
		return user(new KoshUserDetails(admin, false));
	}

	private String sentBody(String to, String subject) {
		ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
		verify(mail).send(eq(to), eq(subject), body.capture());
		return body.getValue();
	}

	private static String token(String body) {
		Matcher link = LINK.matcher(body);
		assertThat(link.find()).as("set-password link in %s", body).isTrue();
		return link.group(1);
	}

}
