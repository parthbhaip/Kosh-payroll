package com.kosh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.thymeleaf.extras.springsecurity6.dialect.SpringSecurityDialect;

@SpringBootTest
@AutoConfigureMockMvc
class KoshApplicationTests {

	private final ApplicationContext context;

	private final MockMvc mvc;

	@Autowired
	KoshApplicationTests(ApplicationContext context, MockMvc mvc) {
		this.context = context;
		this.mvc = mvc;
	}

	@Test
	void contextLoadsWithSecurityDialect() {
		assertThat(context.getBeansOfType(SpringSecurityDialect.class)).hasSize(1);
	}

	@Test
	@WithMockUser
	void htmxIsServedWithoutVersionInUrl() throws Exception {
		mvc.perform(get("/webjars/htmx.org/dist/htmx.min.js"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("htmx")));
	}

	@Test
	void h2ConsoleIsOffOutsideDev() {
		// Devtools defaults spring.h2.console.enabled=true; the base config must override it.
		assertThat(context.getEnvironment().getProperty("spring.h2.console.enabled", Boolean.class)).isFalse();
		assertThat(context.containsBean("h2Console")).isFalse();
	}

}
