package com.kosh.company;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import com.kosh.user.Role;
import com.kosh.user.User;
import com.kosh.user.UserRepository;

/**
 * A logo over 5 MB sent to the real server, which MockMvc cannot show: Tomcat itself refuses the upload with 413 before
 * any Kosh code runs (Tomcat 11 fails the first parameter read, which is Spring Security's CSRF check), and the admin
 * sees the branded "file too large" page instead of a reset connection, then carries on signed in. Runs without a test
 * transaction and deletes its user and their sign-in events afterwards.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class LogoUploadLimitTest {

	private static final String EMAIL = "admin@upload-limit.kosh.test";

	private static final String PASSWORD = "Demo@1234";

	private static final Pattern CSRF_FIELD = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");

	private static final String BOUNDARY = "----kosh-logo-test";

	private static final String CRLF = "\r\n";

	private final UserRepository users;

	private final PasswordEncoder passwordEncoder;

	private final JdbcTemplate jdbc;

	private final String base;

	private final HttpClient browser = HttpClient.newBuilder()
		.cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
		.followRedirects(HttpClient.Redirect.NEVER)
		.build();

	@Autowired
	LogoUploadLimitTest(UserRepository users, PasswordEncoder passwordEncoder, JdbcTemplate jdbc,
			@LocalServerPort int port) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.jdbc = jdbc;
		this.base = "http://localhost:" + port;
	}

	@BeforeEach
	void saveTheAdmin() {
		User admin = new User(EMAIL, "Upload Limit Admin", Set.of(Role.ADMIN));
		admin.setPasswordHash(passwordEncoder.encode(PASSWORD));
		users.save(admin);
	}

	@AfterEach
	void deleteTheAdminAndTheirEvents() {
		users.findByEmailIgnoreCase(EMAIL).ifPresent(user -> {
			jdbc.update("delete from audit_events where entity_type = 'User' and entity_id = ?", user.getId());
			users.delete(user);
		});
	}

	@Test
	void aLogoOverFiveMegabytesGetsTheFileTooLargePageAndTheAdminStaysSignedIn() throws Exception {
		String token = find(CSRF_FIELD, send(page("/login")).body());
		HttpResponse<String> signedIn = send(HttpRequest.newBuilder(URI.create(base + "/login"))
			.header("Content-Type", "application/x-www-form-urlencoded")
			.POST(HttpRequest.BodyPublishers.ofString("email=" + encode(EMAIL) + "&password=" + encode(PASSWORD)
					+ "&_csrf=" + encode(token))));
		assertThat(signedIn.statusCode()).isEqualTo(302);
		assertThat(signedIn.headers().firstValue("Location")).hasValueSatisfying(to -> assertThat(to).endsWith("/admin"));
		String formToken = find(CSRF_FIELD, send(page("/admin/company")).body());

		// Just over the file limit, then well over the request limit (Tomcat refuses that before reading the body).
		for (int bytes : new int[] { 5 * 1024 * 1024 + 512 * 1024, 12 * 1024 * 1024 }) {
			HttpResponse<String> upload = send(page("/admin/company/logo")
				.header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
				.POST(HttpRequest.BodyPublishers.ofByteArray(multipart(formToken, bytes))));
			assertThat(upload.statusCode()).as("%s bytes", bytes).isEqualTo(413);
			assertThat(upload.body()).contains("<title>File too large | Kosh</title>")
				.contains("That file is too large")
				.contains("Uploads can be up to 5 MB. Nothing was saved.");
		}

		HttpResponse<String> after = send(page("/admin/company"));
		assertThat(after.statusCode()).isEqualTo(200);
		assertThat(after.body()).contains("No logo yet.");
	}

	/** What a browser sends for the logo form: the file, then the hidden CSRF field Thymeleaf adds. */
	private static byte[] multipart(String csrfToken, int fileBytes) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		body.writeBytes(("--" + BOUNDARY + CRLF + "Content-Disposition: form-data; name=\"file\"; filename=\"scan.png\""
				+ CRLF + "Content-Type: image/png" + CRLF + CRLF).getBytes(StandardCharsets.US_ASCII));
		body.writeBytes(Arrays.copyOf(CompanySettingsTest.PNG, fileBytes));
		body.writeBytes((CRLF + "--" + BOUNDARY + CRLF + "Content-Disposition: form-data; name=\"_csrf\"" + CRLF + CRLF
				+ csrfToken + CRLF + "--" + BOUNDARY + "--" + CRLF).getBytes(StandardCharsets.US_ASCII));
		return body.toByteArray();
	}

	private HttpRequest.Builder page(String path) {
		return HttpRequest.newBuilder(URI.create(base + path)).header("Accept", "text/html");
	}

	private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
		return browser.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private static String find(Pattern pattern, String html) {
		Matcher matcher = pattern.matcher(html);
		assertThat(matcher.find()).as("%s in the page", pattern).isTrue();
		return matcher.group(1);
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

}
