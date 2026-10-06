package com.kosh.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Outside the dev profile the UI kit does not exist, while the design-system assets are served everywhere. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser("hr@kosh.test")
class UiKitOutsideDevTest {

	private final MockMvc mvc;

	private final ApplicationContext context;

	@Autowired
	UiKitOutsideDevTest(MockMvc mvc, ApplicationContext context) {
		this.mvc = mvc;
		this.context = context;
	}

	@Test
	void uiKitIsNotFoundOutsideDev() throws Exception {
		assertThat(context.getBeansOfType(UiKitController.class)).isEmpty();
		mvc.perform(get("/dev/ui-kit")).andExpect(status().isNotFound());
	}

	@Test
	void designSystemAssetsAreServed() throws Exception {
		mvc.perform(get("/css/app.css")).andExpect(status().isOk()).andExpect(content().string(containsString(".table-wrap")));
		mvc.perform(get("/css/tokens.css"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("font-family: \"IBM Plex Sans\"")));
		mvc.perform(get("/fonts/ibm-plex-sans-latin-400-normal.woff2")).andExpect(status().isOk());
		mvc.perform(get("/fonts/ibm-plex-mono-latin-500-normal.woff2")).andExpect(status().isOk());
		mvc.perform(get("/img/icons.svg")).andExpect(status().isOk()).andExpect(content().string(containsString("id=\"i-rupee\"")));
		mvc.perform(get("/img/logo.svg")).andExpect(status().isOk());
		mvc.perform(get("/img/favicon.svg")).andExpect(status().isOk());
		mvc.perform(get("/img/empty/inbox.svg")).andExpect(status().isOk());
		mvc.perform(get("/js/commands.js")).andExpect(status().isOk()).andExpect(content().string(containsString("showModal")));
	}

	@Test
	void spriteHasEveryIconThePlanNames() throws Exception {
		String sprite = mvc.perform(get("/img/icons.svg")).andExpect(status().isOk()).andReturn().getResponse()
			.getContentAsString();
		for (String name : UiKitController.ICONS) {
			assertThat(sprite).as("icon " + name).contains("<symbol id=\"i-" + name + "\" viewBox=\"0 0 24 24\"");
		}
		assertThat(sprite.split("<symbol ", -1)).hasSize(UiKitController.ICONS.size() + 1);
	}

	@Test
	void stylesKeepTheCurrentPageAndTheSpacingScale() throws Exception {
		String app = mvc.perform(get("/css/app.css")).andReturn().getResponse().getContentAsString();
		String kit = mvc.perform(get("/css/ui-kit.css")).andReturn().getResponse().getContentAsString();
		// Hovering the current page must not repaint it.
		assertThat(app).contains("a.page-link:not([aria-current]):hover").doesNotContain("a.page-link:hover");
		// Spacing stays on the 4px scale: no 6px or 10px gaps, paddings or margins.
		String offScale = "(gap|padding|margin)[a-z-]*:[^;]*(?<![\\d.])(6|10)px";
		assertThat(app).doesNotContainPattern(offScale);
		assertThat(kit).doesNotContainPattern(offScale);
	}

	@Test
	void everyFontFaceIsARealWoff2File() throws Exception {
		for (String font : List.of("sans-latin-400", "sans-latin-500", "sans-latin-600", "sans-latin-700",
				"mono-latin-400", "mono-latin-500")) {
			byte[] body = mvc.perform(get("/fonts/ibm-plex-" + font + "-normal.woff2"))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsByteArray();
			assertThat(new String(body, 0, 4, StandardCharsets.US_ASCII)).as(font).isEqualTo("wOF2");
		}
		mvc.perform(get("/css/tokens.css"))
			.andExpect(content().string(containsString("url(\"../fonts/ibm-plex-mono-latin-500-normal.woff2\")")))
			.andExpect(content().string(containsString("font-display: swap;")));
	}

}
