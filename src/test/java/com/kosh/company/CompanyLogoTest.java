package com.kosh.company;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.kosh.audit.AuditAction;
import com.kosh.audit.AuditEventRepository;
import com.kosh.config.AppProperties;
import com.kosh.storage.FileStorageTest;
import com.kosh.web.Flash;

/**
 * Logo changes that commit (PLAN T11): the replaced file is deleted only after the change commits, a removed logo is
 * gone from disk and from the page, refused files leave nothing behind, and each change is audited. Runs WITHOUT a
 * test transaction, so it puts the company row and the audit log back afterwards.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CompanyLogoTest {

	private static final byte[] PNG = CompanySettingsTest.PNG;

	private static final byte[] JPEG = FileStorageTest.image("jpg", 1, 1);

	private final MockMvc mvc;

	private final CompanyRepository companies;

	private final AuditEventRepository events;

	private final JdbcTemplate jdbc;

	private final Path folder;

	@Autowired
	CompanyLogoTest(MockMvc mvc, CompanyRepository companies, AuditEventRepository events, JdbcTemplate jdbc,
			AppProperties app) {
		this.mvc = mvc;
		this.companies = companies;
		this.events = events;
		this.jdbc = jdbc;
		this.folder = app.uploadDir().resolve(CompanyService.LOGO_FOLDER);
	}

	@BeforeEach
	@AfterEach
	void putTheCompanyBack() throws IOException {
		jdbc.update("update company set logo_path = null where id = 1");
		jdbc.update("delete from audit_events where entity_type = 'Company'");
		for (Path file : files()) {
			Files.delete(file);
		}
	}

	@Test
	void replacingDeletesTheOldFileOnceCommittedAndRemovingDeletesTheLast() throws Exception {
		upload(new MockMultipartFile("file", "logo.png", "image/png", PNG)).andExpect(redirectedUrl("/admin/company"))
			.andExpect(flash().attribute(Flash.ATTRIBUTE, Flash.success("Logo added")
				.withText("Payslips and salary letters generated from now on show it.")));
		String first = logoPath();
		assertThat(files()).containsExactly(folder.getParent().resolve(first));

		upload(new MockMultipartFile("file", "logo.jpg", "image/jpeg", JPEG))
			.andExpect(flash().attribute(Flash.ATTRIBUTE, Flash.success("Logo replaced")
				.withText("Payslips and salary letters generated from now on show it.")));
		String second = logoPath();
		assertThat(second).endsWith(".jpg");
		assertThat(files()).containsExactly(folder.getParent().resolve(second));
		mvc.perform(get("/admin/company/logo").with(admin()))
			.andExpect(status().isOk())
			.andExpect(content().contentType("image/jpeg"))
			.andExpect(content().bytes(JPEG));

		mvc.perform(post("/admin/company/logo/remove").with(admin()).with(csrf()))
			.andExpect(redirectedUrl("/admin/company"))
			.andExpect(flash().attribute(Flash.ATTRIBUTE,
					Flash.success("Logo removed").withText("Payslips and salary letters show the company name instead.")));
		assertThat(logoPath()).isNull();
		assertThat(files()).isEmpty();
		mvc.perform(get("/admin/company/logo").with(admin())).andExpect(status().isNotFound());
		mvc.perform(post("/admin/company/logo/remove").with(admin()).with(csrf()))
			.andExpect(flash().attribute(Flash.ATTRIBUTE, Flash.info("There was no logo to remove")));

		assertThat(summaries()).containsExactly("Added a company logo (PNG)", "Replaced the company logo (JPG)",
				"Removed the company logo");
	}

	@Test
	void refusedFilesLeaveNothingBehind() throws Exception {
		byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(StandardCharsets.UTF_8);
		upload(new MockMultipartFile("file", "logo.png", "image/png", svg)).andExpect(redirectedUrl("/admin/company"))
			.andExpect(flash().attribute(Flash.ATTRIBUTE,
					Flash.danger("The logo wasn’t uploaded").withText("Upload a JPG or PNG file.")));
		upload(new MockMultipartFile("file", "logo.png", "image/png", Arrays.copyOf(PNG, 20)))
			.andExpect(flash().attribute(Flash.ATTRIBUTE, Flash.danger("The logo wasn’t uploaded")
				.withText("The image can’t be read. Save it again as PNG or JPG and upload it.")));
		upload(new MockMultipartFile("file", "", "application/octet-stream", new byte[0]))
			.andExpect(flash().attribute(Flash.ATTRIBUTE,
					Flash.danger("The logo wasn’t uploaded").withText("Choose a file to upload.")));
		mvc.perform(multipart("/admin/company/logo").with(admin()).with(csrf()))
			.andExpect(flash().attribute(Flash.ATTRIBUTE,
					Flash.danger("The logo wasn’t uploaded").withText("Choose a file to upload.")));
		// MockMvc skips the container's 5 MB limit (LogoUploadLimitTest covers it), so this reaches the storage check.
		upload(new MockMultipartFile("file", "huge.png", "image/png", Arrays.copyOf(PNG, 5 * 1024 * 1024 + 1)))
			.andExpect(flash().attribute(Flash.ATTRIBUTE,
					Flash.danger("The logo wasn’t uploaded").withText("The file is larger than 5 MB. Choose a smaller one.")));

		assertThat(logoPath()).isNull();
		assertThat(files()).isEmpty();
		assertThat(summaries()).isEmpty();
	}

	private ResultActions upload(MockMultipartFile file) throws Exception {
		return mvc.perform(multipart("/admin/company/logo").file(file).with(admin()).with(csrf()));
	}

	private static RequestPostProcessor admin() {
		return user("admin@logo.kosh.test").roles("ADMIN");
	}

	private String logoPath() {
		return companies.findById(Company.ID).orElseThrow().getLogoPath();
	}

	private List<Path> files() throws IOException {
		if (!Files.isDirectory(folder)) {
			return List.of();
		}
		try (Stream<Path> listing = Files.list(folder)) {
			return listing.toList();
		}
	}

	private List<String> summaries() {
		return events.findAll()
			.stream()
			.filter(event -> event.getAction() == AuditAction.COMPANY_LOGO_CHANGED)
			.map(event -> event.getSummary())
			.toList();
	}

}
