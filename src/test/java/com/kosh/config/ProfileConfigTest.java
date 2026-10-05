package com.kosh.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * Loads the real property files for each profile through Spring Boot's own config processing, with nothing but
 * {@link AppProperties} in the context, so no database is opened. The machine's environment variables and system
 * properties are replaced (env vars by a map the test chooses, empty by default) so a developer's exported
 * {@code APP_DATA_KEY} cannot change the outcome.
 */
class ProfileConfigTest {

	private static final String KEY = TestDataKeyEnvironmentPostProcessor.generateKey();

	private static final List<String> MYSQL_REQUIRED = List.of("--DB_URL=jdbc:mysql://db.internal:3306/kosh",
			"--DB_USER=kosh_app", "--DB_PASSWORD=s3cret-db", "--APP_DATA_KEY=" + KEY,
			"--APP_BASE_URL=https://payroll.panchganga.in", "--MAIL_FROM=payroll@panchganga.in");

	@Test
	void noProfileMeansDev() {
		try (ConfigurableApplicationContext context = start(null)) {
			Environment env = context.getEnvironment();
			assertThat(env.getActiveProfiles()).isEmpty();
			assertThat(env.getDefaultProfiles()).containsExactly("dev");
			assertThat(env.getProperty("spring.datasource.url")).startsWith("jdbc:h2:file:");
		}
	}

	@Test
	void baseSettingsApplyToEveryProfile() {
		try (ConfigurableApplicationContext context = start("dev")) {
			Environment env = context.getEnvironment();
			assertThat(env.getProperty("spring.application.name")).isEqualTo("Kosh");
			assertThat(env.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
			assertThat(env.getProperty("spring.jpa.open-in-view", Boolean.class)).isFalse();
			assertThat(env.getProperty("server.compression.enabled", Boolean.class)).isTrue();
			assertThat(env.getProperty("spring.web.error.whitelabel.enabled", Boolean.class)).isFalse();
			assertThat(env.getProperty("spring.servlet.multipart.max-file-size")).isEqualTo("5MB");
		}
	}

	@Test
	void devUsesH2FileDatabaseInMysqlModeWithConsoleLogMailAndDemoSeed() {
		try (ConfigurableApplicationContext context = start("dev")) {
			Environment env = context.getEnvironment();
			assertThat(env.getProperty("spring.datasource.url")).startsWith("jdbc:h2:file:./data/kosh;")
				.contains(";MODE=MySQL")
				.contains(";DATABASE_TO_LOWER=TRUE");
			assertThat(env.getProperty("spring.h2.console.enabled", Boolean.class)).isTrue();
			assertThat(env.getProperty("spring.mail.host")).isNull();

			AppProperties app = context.getBean(AppProperties.class);
			assertThat(app.brand()).isEqualTo("Kosh");
			assertThat(app.zone()).isEqualTo(ZoneId.of("Asia/Kolkata"));
			assertThat(app.uploadDir().normalize()).isEqualTo(Path.of("uploads"));
			assertThat(app.seedDemo()).isTrue();
			assertThat(app.dataKeyBytes()).hasSize(32);
			assertThat(app.baseUrl()).isEqualTo("http://localhost:8080");
			assertThat(app.mail().transport()).isEqualTo(AppProperties.Transport.LOG);
			assertThat(app.mail().from()).isEqualTo("no-reply@kosh.test");
		}
	}

	@Test
	void mysqlTakesDatabaseSmtpKeyAndSeedFlagFromEnvironment() {
		List<String> env = new ArrayList<>(MYSQL_REQUIRED);
		env.addAll(List.of("--SEED_DEMO=true", "--UPLOAD_DIR=/srv/kosh/uploads", "--SMTP_HOST=smtp.panchganga.in",
				"--SMTP_PORT=2525", "--SMTP_USERNAME=mailer", "--SMTP_PASSWORD=s3cret-mail"));
		try (ConfigurableApplicationContext context = start("mysql", env)) {
			Environment props = context.getEnvironment();
			assertThat(props.getProperty("spring.datasource.url")).isEqualTo("jdbc:mysql://db.internal:3306/kosh");
			assertThat(props.getProperty("spring.datasource.username")).isEqualTo("kosh_app");
			assertThat(props.getProperty("spring.datasource.password")).isEqualTo("s3cret-db");
			assertThat(props.getProperty("spring.mail.host")).isEqualTo("smtp.panchganga.in");
			assertThat(props.getProperty("spring.mail.port", Integer.class)).isEqualTo(2525);
			assertThat(props.getProperty("spring.mail.username")).isEqualTo("mailer");
			assertThat(props.getProperty("spring.mail.password")).isEqualTo("s3cret-mail");
			assertThat(props.getProperty("spring.mail.properties.mail.smtp.starttls.enable", Boolean.class)).isTrue();
			// Without "required", a server that hides STARTTLS gets the SMTP password in plain text.
			assertThat(props.getProperty("spring.mail.properties.mail.smtp.starttls.required", Boolean.class)).isTrue();
			assertThat(props.getProperty("spring.h2.console.enabled", Boolean.class)).isFalse();

			AppProperties app = context.getBean(AppProperties.class);
			assertThat(app.dataKey()).isEqualTo(KEY);
			assertThat(app.seedDemo()).isTrue();
			assertThat(app.baseUrl()).isEqualTo("https://payroll.panchganga.in");
			assertThat(app.uploadDir()).isEqualTo(Path.of("/srv/kosh/uploads"));
			assertThat(app.mail().transport()).isEqualTo(AppProperties.Transport.SMTP);
			assertThat(app.mail().from()).isEqualTo("payroll@panchganga.in");
		}
	}

	@Test
	void mysqlSwitchesOffDevtoolsPropertyDefaults() {
		// Devtools turns itself off under JUnit, so its defaults (include-stacktrace=always, persistent sessions, ...)
		// cannot be observed here; the switch that skips all of them can.
		try (ConfigurableApplicationContext context = start("mysql", MYSQL_REQUIRED)) {
			Environment env = context.getEnvironment();
			assertThat(env.getProperty("spring.devtools.add-properties", Boolean.class)).isFalse();
			// LiveReload listens on all interfaces without authentication.
			assertThat(env.getProperty("spring.devtools.livereload.enabled", Boolean.class)).isFalse();
		}
	}

	@Test
	void mysqlDoesNotSeedDemoDataUnlessSeedDemoIsSet() {
		try (ConfigurableApplicationContext context = start("mysql", MYSQL_REQUIRED)) {
			assertThat(context.getBean(AppProperties.class).seedDemo()).isFalse();
		}
	}

	@Test
	void mysqlRefusesToStartWithoutDataKey() {
		List<String> withoutKey = MYSQL_REQUIRED.stream().filter((arg) -> !arg.startsWith("--APP_DATA_KEY")).toList();
		assertThatThrownBy(() -> start("mysql", withoutKey))
			.hasStackTraceContaining("app.data-key must be base64 of exactly 32 random bytes")
			.hasStackTraceContaining("APP_DATA_KEY");

		List<String> blankKey = new ArrayList<>(withoutKey);
		blankKey.add("--APP_DATA_KEY=");
		assertThatThrownBy(() -> start("mysql", blankKey))
			.hasStackTraceContaining("must be set; the mysql profile reads it from APP_DATA_KEY");
	}

	@ParameterizedTest
	@ValueSource(strings = { "APP_BASE_URL", "MAIL_FROM" })
	void mysqlRefusesToStartWithoutBaseUrlOrSender(String variable) {
		// No fallback: localhost links or a .test sender must not reach real users. The binder keeps the unresolved
		// placeholder, which fails validation and shows up as the rejected value.
		List<String> without = MYSQL_REQUIRED.stream().filter((arg) -> !arg.startsWith("--" + variable + "=")).toList();
		assertThatThrownBy(() -> start("mysql", without))
			.hasStackTraceContaining("rejected value [${" + variable + "}]");
	}

	@Test
	void testProfileNeverUsesAnExportedDataKey() {
		// Relaxed binding maps APP_DATA_KEY onto app.data-key; the generated test key must still win.
		String exported = TestDataKeyEnvironmentPostProcessor.generateKey();
		try (ConfigurableApplicationContext context = start("test", List.of(), Map.of("APP_DATA_KEY", exported))) {
			Object generated = context.getEnvironment()
				.getPropertySources()
				.get(TestDataKeyEnvironmentPostProcessor.PROPERTY_SOURCE)
				.getProperty("app.data-key");
			assertThat(context.getBean(AppProperties.class).dataKey()).isEqualTo(generated).isNotEqualTo(exported);
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "not base64 at all!", "c2hvcnQ=", "AAAAAAAAAAAAAAAAAAAAAA==" })
	void dataKeyMustBeBase64OfExactly32Bytes(String badKey) {
		assertThatThrownBy(() -> start("dev", List.of("--app.data-key=" + badKey)))
			.hasStackTraceContaining("base64 of exactly 32 random bytes");
	}

	@ParameterizedTest
	@ValueSource(strings = { "http://localhost:8080/", "localhost:8080", "ftp://files.example.in" })
	void baseUrlMustBeAbsoluteHttpWithoutTrailingSlash(String badUrl) {
		assertThatThrownBy(() -> start("dev", List.of("--app.base-url=" + badUrl)))
			.hasStackTraceContaining("absolute http(s) URL without a trailing slash");
	}

	@Test
	void missingMailTransportStopsStartup() {
		// The @NotNull on Mail.transport itself must fail, not just any binding error under app.mail.transport.
		assertThatThrownBy(() -> start("dev", List.of("--app.mail.transport="))).rootCause()
			.isInstanceOfSatisfying(BindValidationException.class,
					(ex) -> assertThat(ex.getValidationErrors()).singleElement()
						.satisfies((error) -> assertThat(error.getCodes()).contains("NotNull.app.mail.transport")));
	}

	@Test
	void toStringNeverShowsTheDataKey() {
		try (ConfigurableApplicationContext context = start("dev")) {
			AppProperties app = context.getBean(AppProperties.class);
			assertThat(app.toString()).doesNotContain(app.dataKey()).contains("dataKey=[hidden]");
		}
	}

	@Test
	void envExampleListsEveryEnvironmentVariableTheConfigReads() throws IOException {
		Set<String> referenced = new TreeSet<>();
		Pattern placeholder = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*)");
		try (Stream<Path> files = Files.list(Path.of("src/main/resources"))) {
			for (Path file : files.filter((f) -> f.getFileName().toString().matches("application.*\\.properties"))
				.toList()) {
				Matcher matcher = placeholder.matcher(Files.readString(file));
				while (matcher.find()) {
					referenced.add(matcher.group(1));
				}
			}
		}
		Set<String> documented = new TreeSet<>();
		for (String line : Files.readAllLines(Path.of(".env.example"))) {
			if (line.matches("[A-Z][A-Z0-9_]*=.*")) {
				documented.add(line.substring(0, line.indexOf('=')));
			}
		}
		assertThat(referenced).contains("DB_URL", "DB_USER", "DB_PASSWORD", "APP_DATA_KEY", "SEED_DEMO", "SMTP_HOST");
		assertThat(documented).containsAll(referenced).contains("SPRING_PROFILES_ACTIVE");
	}

	private static ConfigurableApplicationContext start(String profile) {
		return start(profile, List.of());
	}

	private static ConfigurableApplicationContext start(String profile, List<String> args) {
		return start(profile, args, Map.of());
	}

	private static ConfigurableApplicationContext start(String profile, List<String> args, Map<String, Object> envVars) {
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources()
			.replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, new SystemEnvironmentPropertySource(
					StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, envVars));
		environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
		SpringApplication application = new SpringApplication(PropertiesOnly.class);
		application.setEnvironment(environment);
		application.setWebApplicationType(WebApplicationType.NONE);
		application.setBannerMode(Banner.Mode.OFF);
		application.setLogStartupInfo(false);
		List<String> all = new ArrayList<>(args);
		if (profile != null) {
			all.add("--spring.profiles.active=" + profile);
		}
		return application.run(all.toArray(String[]::new));
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(AppProperties.class)
	static class PropertiesOnly {

	}

}
