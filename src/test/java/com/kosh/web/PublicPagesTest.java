package com.kosh.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.RequestDispatcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.kosh.security.SecurityConfig;

/** The public layout and pages, open to anonymous visitors, with the security headers from CLAUDE.md. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PublicPagesTest {

	private static final Pattern HTMX_SRC = Pattern.compile("<script src=\"(/webjars/htmx\\.org/[^\"]*/htmx\\.min\\.js)\" defer>");

	private final MockMvc mvc;

	private final UserDetailsService users;

	@Autowired
	PublicPagesTest(MockMvc mvc, UserDetailsService users) {
		this.mvc = mvc;
		this.users = users;
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', textBlock = """
			/        | public/home    | Payroll that closes on time | Payroll that closes on time.
			/privacy | public/privacy | Privacy                     | Privacy notice
			/terms   | public/terms   | Terms of use                | Terms of use
			/help    | public/help    | Help                        | Help and questions
			""")
	void publicPagesOpenForAnonymousVisitorsInThePublicLayout(String path, String viewName, String title, String heading)
			throws Exception {
		MvcResult result = mvc.perform(get(path))
			.andExpect(status().isOk())
			.andExpect(view().name(viewName))
			.andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
			.andExpect(content().string(containsString("<html lang=\"en\">")))
			.andExpect(content().string(containsString("<title>" + title + " | Kosh</title>")))
			.andExpect(content().string(containsString(heading)))
			.andReturn();
		String page = result.getResponse().getContentAsString();

		// Public layout: skip link, header with logo and sign-in, main landmark, footer with legal and help links.
		assertThat(page).contains("<a class=\"skip-link\" href=\"#main\">Skip to content</a>")
			.contains("<header class=\"site-header\">")
			.contains("<img src=\"/img/logo.svg\" alt=\"Kosh home\"")
			.contains("<a class=\"btn btn-primary btn-sm\" href=\"/login\">Sign in</a>")
			.contains("<main class=\"site-main\" id=\"main\" tabindex=\"-1\">")
			.contains("<footer class=\"site-footer\">")
			.contains("href=\"/privacy\">Privacy</a>")
			.contains("href=\"/terms\">Terms</a>")
			.contains("href=\"/help\">Help</a>")
			.contains("<link rel=\"icon\" type=\"image/svg+xml\" href=\"/img/favicon.svg\">")
			.doesNotContain("action=\"/logout\"")
			.doesNotContain("th:");
		// htmx, its config and the dialog fallback on every page; no CSRF meta tags and no session for anonymous visitors.
		assertThat(page).containsPattern(HTMX_SRC)
			.contains("<script src=\"/js/htmx-config.js\" defer></script>")
			.contains("<script src=\"/js/commands.js\" defer></script>");
		assertThat(page).doesNotContain("name=\"_csrf\"");
		assertThat(result.getRequest().getSession(false)).isNull();
	}

	@Test
	void landingPageHasHeroFeaturesRunStepsComplianceAndSignIn() throws Exception {
		String page = mvc.perform(get("/")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

		assertThat(page).contains("<h1 class=\"hero-title\" id=\"hero-title\">Payroll that closes on time.</h1>")
			.contains("<a class=\"btn btn-primary\" href=\"/login\">Sign in to Kosh</a>")
			.contains("id=\"features\"")
			.contains("id=\"how-it-works\"")
			.contains("How a payroll run works");
		assertThat(count(page, "<li class=\"card feature\">")).isEqualTo(6);
		assertThat(count(page, "<li class=\"run-step\">")).isEqualTo(4);
		for (String code : new String[] { "PF", "ESI", "PT", "TDS" }) {
			assertThat(page).contains("<p class=\"compliance-code\">" + code + "</p>");
		}
		// The example run adds up: gross - deductions = net.
		assertThat(page).contains("₹74,10,250.00").contains("₹11,61,934.50").contains("₹62,48,315.50");
		// Every in-page link has its target.
		assertThat(page).contains("href=\"#how-it-works\"").contains("href=\"/#features\"").contains("href=\"/#how-it-works\"");
	}

	@Test
	void helpPageAnswersQuestionsInDisclosureWidgets() throws Exception {
		String page = mvc.perform(get("/help")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

		assertThat(count(page, "<details class=\"faq-item\">")).isGreaterThanOrEqualTo(10);
		assertThat(page).contains("I forgot my password")
			.contains("not the official TRACES certificate")
			.contains("Managers never see")
			.contains("Still need help?");
	}

	@Test
	void legalPagesCarryTheirDateAndTheFilingLabels() throws Exception {
		mvc.perform(get("/privacy"))
			.andExpect(content().string(containsString("Last updated 5 October 2026")))
			.andExpect(content().string(containsString("Kosh never stores Aadhaar numbers.")))
			.andExpect(content().string(containsString("XXXXXX4321")));
		mvc.perform(get("/terms"))
			.andExpect(content().string(containsString("Last updated 5 October 2026")))
			.andExpect(content().string(containsString("Prepared for filing - verify before upload")))
			.andExpect(content().string(containsString("not the official TRACES")));
	}

	@Test
	void securityHeadersOnPublicPagesAssetsAndRedirects() throws Exception {
		expectSecurityHeaders(mvc.perform(get("/")));
		expectSecurityHeaders(mvc.perform(get("/help")));
		expectSecurityHeaders(mvc.perform(get("/css/app.css")));
		expectSecurityHeaders(mvc.perform(get("/hr/employees")));
		// Error pages are not checked here: MockMvc can only simulate the container's error dispatch, which Spring
		// Security's header filter skips; in the container the headers are already on the response by then.
	}

	@Test
	void contentSecurityPolicyIsExactlyTheOneInClaudeMd() {
		assertThat(SecurityConfig.CONTENT_SECURITY_POLICY).isEqualTo("default-src 'self'; script-src 'self'; "
				+ "img-src 'self' data:; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'");
	}

	@Test
	void stylesScriptsFontsAndImagesAreOpenToAnonymousVisitors() throws Exception {
		String page = mvc.perform(get("/")).andReturn().getResponse().getContentAsString();
		Matcher htmx = HTMX_SRC.matcher(page);
		assertThat(htmx.find()).isTrue();
		mvc.perform(get(htmx.group(1))).andExpect(status().isOk()).andExpect(content().string(containsString("htmx")));

		mvc.perform(get("/js/htmx-config.js"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("htmx.config.includeIndicatorStyles = false;")))
			.andExpect(content().string(containsString("htmx.config.allowEval = false;")))
			.andExpect(content().string(containsString("htmx:configRequest")))
			.andExpect(content().string(containsString("meta[name=\"_csrf_header\"]")));
		mvc.perform(get("/css/tokens.css")).andExpect(status().isOk());
		mvc.perform(get("/css/app.css"))
			.andExpect(status().isOk())
			// htmx's own indicator styles are off, so the indicator classes live in app.css.
			.andExpect(content().string(containsString(".htmx-request .htmx-indicator")));
		mvc.perform(get("/fonts/ibm-plex-sans-latin-400-normal.woff2")).andExpect(status().isOk());
		mvc.perform(get("/img/logo.svg")).andExpect(status().isOk());
		mvc.perform(get("/img/icons.svg")).andExpect(status().isOk());
		mvc.perform(get("/img/empty/people.svg")).andExpect(status().isOk());
	}

	@Test
	void anonymousErrorPagesUseThePublicLayout() throws Exception {
		mvc.perform(get("/error").accept(MediaType.TEXT_HTML)
			.requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 404)
			.requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/nowhere"))
			.andExpect(status().isNotFound())
			.andExpect(view().name("error/404"))
			.andExpect(content().string(containsString("<title>Page not found | Kosh</title>")))
			.andExpect(content().string(containsString("<header class=\"site-header\">")))
			.andExpect(content().string(containsString("<footer class=\"site-footer\">")))
			.andExpect(content().string(containsString("We can’t find that page")));
	}

	@Test
	void bootGeneratesNoDefaultAccount() {
		// Boot's generated "user" would sign in with a password it writes to the log.
		assertThatThrownBy(() -> users.loadUserByUsername("user")).isInstanceOf(UsernameNotFoundException.class);
	}

	@Test
	void signedInAreasSendAnonymousVisitorsToSignIn() throws Exception {
		mvc.perform(get("/hr/employees")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login"));
		mvc.perform(get("/me/payslips")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login"));
		mvc.perform(get("/notifications")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login"));
	}

	private static void expectSecurityHeaders(ResultActions result) throws Exception {
		result.andExpect(header().string("Content-Security-Policy", SecurityConfig.CONTENT_SECURITY_POLICY))
			.andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'none'")))
			.andExpect(header().string("X-Content-Type-Options", "nosniff"))
			.andExpect(header().string("X-Frame-Options", "DENY"))
			.andExpect(header().string("Referrer-Policy", "same-origin"));
	}

	private static int count(String text, String needle) {
		return text.split(Pattern.quote(needle), -1).length - 1;
	}

}
