package com.kosh.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * In dev the H2 console gets its own filter chain: ADMIN only, framed by itself, with the relaxed policy it needs.
 * MockMvc only reaches the DispatcherServlet, so an admin gets 404 here; the console servlet answers in a real run.
 * Test profile listed last so its in-memory database and seed-off settings win.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({ "dev", "test" })
class H2ConsoleSecurityTest {

	private final MockMvc mvc;

	private final ApplicationContext context;

	@Autowired
	H2ConsoleSecurityTest(MockMvc mvc, ApplicationContext context) {
		this.mvc = mvc;
		this.context = context;
	}

	@Test
	void consoleNeedsAnAdminSignIn() throws Exception {
		assertThat(context.getBeansOfType(SecurityFilterChain.class)).containsOnlyKeys("h2ConsoleSecurity", "webSecurity");
		mvc.perform(get("/h2-console")).andExpect(status().isFound()).andExpect(redirectedUrl("/login"));
		mvc.perform(get("/h2-console/login.do").with(user("hr@kosh.test").roles("HR", "EMPLOYEE")))
			.andExpect(status().isForbidden());
	}

	@Test
	void adminGetsTheConsolePolicyWithoutCsrf() throws Exception {
		mvc.perform(post("/h2-console/login.do").with(user("admin@kosh.test").roles("ADMIN")))
			.andExpect(status().isNotFound())
			.andExpect(header().string("Content-Security-Policy", SecurityConfig.H2_CONSOLE_POLICY))
			.andExpect(header().string("X-Frame-Options", "SAMEORIGIN"));
	}

	@Test
	void everythingElseKeepsTheStrictPolicy() throws Exception {
		mvc.perform(get("/admin").with(user("admin@kosh.test").roles("ADMIN")))
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Security-Policy", SecurityConfig.CONTENT_SECURITY_POLICY))
			.andExpect(header().string("X-Frame-Options", "DENY"));
		mvc.perform(get("/h2-consolex")).andExpect(redirectedUrl("/login"));
	}

}
