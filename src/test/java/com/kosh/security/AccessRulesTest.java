package com.kosh.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.kosh.user.Role;

/**
 * The URL rules (PLAN T07) against the real area homes. Each row is the specification for a set of roles: 200 opens
 * the page, 403 is outside the user's areas, 404 passes security but has no page yet ({@code /team} arrives with T51).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccessRulesTest {

	private final MockMvc mvc;

	@Autowired
	AccessRulesTest(MockMvc mvc) {
		this.mvc = mvc;
	}

	@ParameterizedTest(name = "{0}")
	@CsvSource(delimiter = '|', textBlock = """
			ADMIN                  | 200 | 200 | 200     | 200     | 403 | 403
			HR                     | 403 | 200 | 403     | 403     | 403 | 403
			PAYROLL                | 403 | 403 | 200     | 403     | 403 | 403
			FINANCE                | 403 | 403 | 403     | 200     | 403 | 403
			MANAGER                | 403 | 403 | 403     | 403     | 403 | 404
			EMPLOYEE               | 403 | 403 | 403     | 403     | 200 | 403
			HR PAYROLL EMPLOYEE    | 403 | 200 | 200     | 403     | 200 | 403
			FINANCE MANAGER EMPLOYEE | 403 | 403 | 403   | 200     | 200 | 404
			""")
	void eachRoleOpensItsOwnAreasOnly(String roles, int admin, int hr, int payroll, int finance, int me, int team)
			throws Exception {
		String[] granted = roles.split(" ");
		expect("/admin", granted, admin);
		expect("/hr", granted, hr);
		expect("/payroll", granted, payroll);
		expect("/finance", granted, finance);
		expect("/me", granted, me);
		expect("/team/approvals", granted, team);
		// Paths below an area follow the area: 404 where the role is allowed (no page yet), 403 where it is not.
		expect("/admin/users", granted, admin);
		expect("/admin/statutory", granted, (admin == 200) ? 404 : admin);
		expect("/hr/employees", granted, (hr == 200) ? 404 : hr);
		expect("/me/payslips", granted, (me == 200) ? 404 : me);
	}

	@ParameterizedTest
	@ValueSource(strings = { "/admin", "/hr", "/payroll", "/finance", "/me", "/team", "/hr/employees", "/me/payslips",
			"/home", "/notifications", "/dev/ui-kit" })
	void anonymousVisitorsAreSentToSignIn(String path) throws Exception {
		mvc.perform(get(path)).andExpect(status().isFound()).andExpect(redirectedUrl("/login"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "/", "/privacy", "/terms", "/help", "/login", "/css/app.css", "/js/password-toggle.js",
			"/img/logo.svg" })
	void publicPagesAndAssetsOpenWithoutSigningIn(String path) throws Exception {
		mvc.perform(get(path)).andExpect(status().isOk());
	}

	@ParameterizedTest
	@ValueSource(strings = { "/password/forgot", "/password/reset", "/password/set" })
	void passwordLinksFromEmailsAreOpenWithoutSigningIn(String path) throws Exception {
		// Without a token, set and reset show "This link no longer works"; forgot shows its form.
		mvc.perform(get(path)).andExpect(status().isOk());
	}

	@Test
	void changingYourPasswordNeedsASignInButNoParticularRole() throws Exception {
		mvc.perform(get("/account/password")).andExpect(redirectedUrl("/login"));
		for (Role role : Role.values()) {
			expect("/account/password", new String[] { role.name() }, 200);
		}
	}

	private void expect(String path, String[] roles, int status) throws Exception {
		int actual = mvc.perform(get(path).with(user("someone@access.kosh.test").roles(roles)))
			.andReturn()
			.getResponse()
			.getStatus();
		assertThat(actual).as("GET %s as %s", path, Arrays.toString(roles)).isEqualTo(status);
	}

}
