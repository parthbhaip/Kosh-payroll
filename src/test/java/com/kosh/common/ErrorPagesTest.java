package com.kosh.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import jakarta.servlet.RequestDispatcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.stereotype.Controller;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Application exceptions go through {@link ErrorPageAdvice}; container errors (unknown URL, Spring Security's 403,
 * uncaught exceptions) go through Boot's {@code /error} controller, simulated here with the servlet error attributes
 * because MockMvc does not perform the container's error dispatch.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser("asha@kosh.test")
@ExtendWith(OutputCaptureExtension.class)
class ErrorPagesTest {

	private final MockMvc mvc;

	@Autowired
	ErrorPagesTest(MockMvc mvc) {
		this.mvc = mvc;
	}

	@Test
	void notFoundExceptionShowsTheBranded404() throws Exception {
		mvc.perform(get("/probe/missing"))
			.andExpect(status().isNotFound())
			.andExpect(view().name("error/404"))
			.andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
			.andExpect(content().string(containsString("<title>Page not found | Kosh</title>")))
			.andExpect(content().string(containsString("We can’t find that page")))
			.andExpect(content().string(containsString("Error 404")))
			.andExpect(content().string(containsString("href=\"/css/app.css\"")))
			.andExpect(content().string(not(containsString("Probe 42"))));
	}

	@Test
	void forbiddenExceptionShowsTheBranded403AndLogsTheDenial(CapturedOutput output) throws Exception {
		mvc.perform(get("/probe/forbidden").queryParam("pan", "ABCDE1234F"))
			.andExpect(status().isForbidden())
			.andExpect(view().name("error/403"))
			.andExpect(content().string(containsString("<title>Access denied | Kosh</title>")))
			.andExpect(content().string(containsString("You don’t have access to this page")))
			.andExpect(content().string(containsString("If you think you should have access, contact your Kosh administrator.")))
			.andExpect(content().string(not(containsString("salary of employee"))));
		assertThat(output.getOut()).contains("WARN")
			.contains("Access denied: user=asha@kosh.test GET /probe/forbidden")
			.doesNotContain("salary of employee")
			.doesNotContain("ABCDE1234F");
	}

	@Test
	void containerErrorsUseTheSameBrandedPages() throws Exception {
		mvc.perform(containerError(404))
			.andExpect(status().isNotFound())
			.andExpect(view().name("error/404"))
			.andExpect(content().string(containsString("We can’t find that page")));
		mvc.perform(containerError(403))
			.andExpect(status().isForbidden())
			.andExpect(view().name("error/403"))
			.andExpect(content().string(containsString("You don’t have access to this page")));
	}

	@Test
	void uncaughtExceptionShowsTheBranded500WithoutDetails() throws Exception {
		mvc.perform(containerError(500)
			.requestAttr(RequestDispatcher.ERROR_EXCEPTION, new IllegalStateException("PAN ABCDE1234F rejected"))
			.requestAttr(RequestDispatcher.ERROR_MESSAGE, "PAN ABCDE1234F rejected"))
			.andExpect(status().isInternalServerError())
			.andExpect(view().name("error/500"))
			.andExpect(content().string(containsString("<title>Something went wrong | Kosh</title>")))
			.andExpect(content().string(containsString("Something went wrong on our side")))
			.andExpect(content().string(not(containsString("ABCDE1234F"))))
			.andExpect(content().string(not(containsString("IllegalStateException"))));
	}

	@Test
	void otherStatusesFallBackToTheGenericBrandedPage() throws Exception {
		mvc.perform(containerError(405))
			.andExpect(status().isMethodNotAllowed())
			.andExpect(view().name("error"))
			.andExpect(content().string(containsString("<title>Request not completed | Kosh</title>")))
			.andExpect(content().string(containsString("Error 405")))
			.andExpect(content().string(containsString("405 Method Not Allowed")));
	}

	@Test
	void errorPageStylesheetsAreServed() throws Exception {
		mvc.perform(get("/css/tokens.css"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("--color-primary: #2647B5")));
		mvc.perform(get("/css/app.css")).andExpect(status().isOk()).andExpect(content().string(containsString(".error-card")));
	}

	private static MockHttpServletRequestBuilder containerError(int status) {
		return get("/error").accept(MediaType.TEXT_HTML)
			.requestAttr(RequestDispatcher.ERROR_STATUS_CODE, status)
			.requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/somewhere");
	}

	@TestConfiguration(proxyBeanMethods = false)
	@Import(ProbeController.class)
	static class ProbeConfig {

	}

	@Controller
	static class ProbeController {

		@GetMapping("/probe/missing")
		String missing() {
			throw new NotFoundException("Probe 42 does not exist");
		}

		@GetMapping("/probe/forbidden")
		String forbidden() {
			throw new ForbiddenException("Not allowed to see the salary of employee 7");
		}

	}

}
