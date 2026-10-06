package com.kosh.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code /dev/ui-kit} under the dev profile. "test" is listed after "dev" so its in-memory database and seed-off
 * settings win; the dev-only controller still sees an active "dev" profile.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({ "dev", "test" })
@WithMockUser("hr@kosh.test")
class UiKitPageTest {

	private final MockMvc mvc;

	private final Environment environment;

	@Autowired
	UiKitPageTest(MockMvc mvc, Environment environment) {
		this.mvc = mvc;
		this.environment = environment;
	}

	@Test
	void runsOnTheInMemoryDatabaseNotDevsFile() {
		assertThat(environment.getProperty("spring.datasource.url")).startsWith("jdbc:h2:mem:");
		assertThat(environment.getProperty("app.seed-demo", Boolean.class)).isFalse();
	}

	@Test
	void rendersEveryComponentSection() throws Exception {
		var page = mvc.perform(get("/dev/ui-kit"))
			.andExpect(status().isOk())
			.andExpect(view().name("dev/ui-kit"))
			.andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
			.andExpect(content().string(containsString("<html lang=\"en\">")))
			.andExpect(content().string(containsString("<title>UI kit | Kosh</title>")))
			.andExpect(content().string(containsString("<link rel=\"icon\" type=\"image/svg+xml\" href=\"/img/favicon.svg\">")))
			.andExpect(content().string(containsString("href=\"/css/tokens.css\"")))
			.andExpect(content().string(containsString("href=\"/css/app.css\"")))
			.andExpect(content().string(containsString("href=\"/css/ui-kit.css\"")))
			.andExpect(content().string(containsString("<script src=\"/js/commands.js\" defer></script>")))
			.andExpect(content().string(containsString("href=\"/fonts/ibm-plex-sans-latin-400-normal.woff2\"")))
			.andExpect(content().string(not(containsString("th:"))))
			.andReturn()
			.getResponse()
			.getContentAsString();

		for (String section : List.of("brand", "colour", "type", "icons", "buttons", "forms", "cards", "badges",
				"tables", "tabs", "stepper", "timeline", "details", "feedback", "dialogs", "empty", "loading",
				"pagination")) {
			assertThat(page).as("section " + section).contains("id=\"" + section + "\"").contains("href=\"#" + section + "\"");
		}
		assertThat(page).contains("<a class=\"skip-link\" href=\"#main\">").contains("<main id=\"main\"");
	}

	@Test
	void showsEveryIconFromTheSprite() throws Exception {
		String page = render();
		for (String icon : UiKitController.ICONS) {
			assertThat(page).as("icon " + icon).contains("<use href=\"/img/icons.svg#i-" + icon + "\"></use>");
		}
		assertThat(page).contains("<svg class=\"icon\" aria-hidden=\"true\" focusable=\"false\">");
	}

	@Test
	void showsButtonAndFormStates() throws Exception {
		assertThat(render()).contains("class=\"btn btn-primary\"", "class=\"btn btn-secondary\"", "class=\"btn btn-ghost\"",
				"class=\"btn btn-danger\"", "class=\"btn btn-primary btn-sm\"", "type=\"button\" disabled>",
				// A busy button is natively disabled, so Enter or Space cannot submit it twice.
				"aria-busy=\"true\" disabled>Calculating payroll",
				"aria-label=\"Edit employee\"",
				// Every input is labelled; errors are announced and tied to the input.
				"<label class=\"label\" for=\"kit-pan\">PAN</label>",
				"aria-invalid=\"true\" aria-describedby=\"kit-pan-error\"",
				"<p class=\"error-text\" id=\"kit-pan-error\">",
				"<p class=\"help\" id=\"kit-email-help\">", "<select class=\"select\" id=\"kit-department\"",
				"type=\"date\" value=\"2026-10-01\"", "<textarea class=\"textarea\"", "readonly", "disabled>",
				"class=\"checkbox\" type=\"checkbox\"", "class=\"radio\" type=\"radio\"",
				"class=\"switch\" type=\"checkbox\" role=\"switch\"", "<legend>Tax regime for 2026-27</legend>");
	}

	@Test
	void formatsMoneyWithIndianGroupingAndMarksNegatives() throws Exception {
		assertThat(render())
			// Rows: net = gross - deductions + adjustment.
			.contains("<td class=\"num\">₹96,500.00</td>", "<td class=\"num\">₹81,638.00</td>")
			.contains("<td class=\"num neg\">-₹2,500.00</td>", "<td class=\"num neg\">-₹1,250.00</td>")
			// Totals row.
			.contains("<td class=\"num\">₹2,98,200.00</td>", "<td class=\"num\">₹40,876.00</td>",
					"<td class=\"num neg\">-₹1,900.00</td>", "<td class=\"num\">₹2,55,424.00</td>")
			// Stat cards and copy.
			.contains("<p class=\"stat-value\">₹62.5 L</p>", "<p class=\"stat-value\">₹9.9 L</p>", "₹62,48,315.50")
			// Masked identifiers.
			.contains(">BQXXXXX1K<", ">XXXXXX4321<")
			.doesNotContain("BQRPD4821K", "50100234564321");
	}

	@Test
	void showsTableNavigationAndStatusComponents() throws Exception {
		assertThat(render()).contains("<div class=\"table-wrap\">", "<caption>Payroll register, September 2026</caption>",
				"<th scope=\"col\" aria-sort=\"ascending\"><a class=\"sort\"", "<th scope=\"row\">", "<tfoot>",
				"<form class=\"filter-bar\" method=\"get\"", "role=\"search\"",
				"<nav class=\"pagination\" aria-label=\"Payroll register pages\">",
				"aria-current=\"page\" aria-label=\"Page 1\"", "<span class=\"page-link page-prev\" aria-disabled=\"true\">",
				"<nav class=\"tabs\" aria-label=\"Employee record\">", "<a class=\"tab\" href=\"#tabs\" aria-current=\"page\">",
				"<ol class=\"stepper\"", "<li class=\"step is-complete\">", "<li class=\"step\" aria-current=\"step\">",
				"<ol class=\"timeline\">", "<li class=\"timeline-item is-danger\">", "<dl class=\"dl\">",
				"<a class=\"chip\" href=\"#badges\" aria-current=\"true\">");
	}

	@Test
	void showsEveryPayrollAndEmployeeStatusBadge() throws Exception {
		String page = render();
		for (String state : List.of("DRAFT", "INPUTS_LOCKED", "CALCULATED", "SUBMITTED", "APPROVED", "REJECTED",
				"FINALIZED", "PAID", "REOPENED", "ACTIVE", "ON_NOTICE", "EXITED", "VOID")) {
			assertThat(page).as("badge " + state).contains("<span class=\"badge\" data-status=\"" + state + "\">");
		}
	}

	@Test
	void showsFeedbackDialogsEmptyStatesAndSkeletons() throws Exception {
		assertThat(render()).contains("<div class=\"flash flash-success\" role=\"status\">",
				"<div class=\"flash flash-warning\" role=\"status\">", "<div class=\"flash flash-danger\" role=\"alert\">",
				"<div class=\"toast-region kit-toasts\" aria-live=\"polite\">", "<div class=\"toast toast-danger\">",
				// Native dialogs: opened by invoker buttons, closed by method="dialog" forms.
				"<dialog class=\"modal\" id=\"kit-modal\" aria-labelledby=\"kit-modal-title\">",
				"commandfor=\"kit-modal\" command=\"show-modal\"", "<dialog class=\"drawer\" id=\"kit-drawer\"",
				"commandfor=\"kit-drawer\" command=\"show-modal\"", "<form class=\"dialog-footer\" method=\"dialog\">",
				"aria-label=\"Close\"",
				"<img src=\"/img/empty/people.svg\" alt=\"\"", "<img src=\"/img/empty/documents.svg\" alt=\"\"",
				"<img src=\"/img/empty/search.svg\" alt=\"\"", "<img src=\"/img/empty/inbox.svg\" alt=\"\"",
				"<div class=\"stat-grid\" aria-busy=\"true\">", "<p class=\"visually-hidden\">Loading payroll summary</p>",
				"class=\"skeleton skeleton-value\"", "<img src=\"/img/logo.svg\" alt=\"Kosh\"");
	}

	private String render() throws Exception {
		return mvc.perform(get("/dev/ui-kit"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
	}

}
