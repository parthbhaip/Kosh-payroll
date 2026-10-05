package com.kosh.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZoneId;

import javax.sql.DataSource;

import jakarta.persistence.EntityManagerFactory;
import jakarta.servlet.MultipartConfigElement;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;
import org.springframework.test.context.ActiveProfiles;

/** The full application on the {@code test} profile, on a real port so response compression can be observed. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class TestProfileTest {

	private final ApplicationContext context;

	private final AppProperties app;

	private final DataSource dataSource;

	private final EntityManagerFactory entityManagerFactory;

	private final int port;

	@Autowired
	TestProfileTest(ApplicationContext context, AppProperties app, DataSource dataSource,
			EntityManagerFactory entityManagerFactory, @LocalServerPort int port) {
		this.context = context;
		this.app = app;
		this.dataSource = dataSource;
		this.entityManagerFactory = entityManagerFactory;
		this.port = port;
	}

	@Test
	void runsOnInMemoryH2InMysqlMode() throws SQLException {
		assertThat(context.getEnvironment().getActiveProfiles()).containsExactly("test");
		try (Connection connection = dataSource.getConnection();
				ResultSet mode = connection.createStatement()
					.executeQuery("SELECT setting_value FROM information_schema.settings WHERE setting_name = 'MODE'")) {
			assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:kosh");
			assertThat(mode.next()).isTrue();
			assertThat(mode.getString(1)).isEqualTo("MySQL");
		}
	}

	@Test
	void appPropertiesBindWithAGeneratedDataKey() {
		assertThat(app.brand()).isEqualTo("Kosh");
		assertThat(app.zone()).isEqualTo(ZoneId.of("Asia/Kolkata"));
		assertThat(app.seedDemo()).isFalse();
		assertThat(app.mail().transport()).isEqualTo(AppProperties.Transport.LOG);
		assertThat(app.dataKeyBytes()).hasSize(32);
		assertThat(app.dataKey()).isEqualTo(((ConfigurableEnvironment) context.getEnvironment()).getPropertySources()
			.get(TestDataKeyEnvironmentPostProcessor.PROPERTY_SOURCE)
			.getProperty("app.data-key"));
	}

	@Test
	void hibernateOnlyValidatesTheFlywaySchema() {
		assertThat(entityManagerFactory.getProperties()).containsEntry("hibernate.hbm2ddl.auto", "validate");
	}

	@Test
	void openSessionInViewIsOff() {
		assertThat(context.getBeanNamesForType(OpenEntityManagerInViewInterceptor.class)).isEmpty();
	}

	@Test
	void whitelabelErrorPageIsOff() {
		// Boot registers its whitelabel page as a View bean named "error" only while it is enabled.
		assertThat(context.containsBean("error")).isFalse();
	}

	@Test
	void uploadsAreCappedAtFiveMegabytesPerFile() {
		MultipartConfigElement multipart = context.getBean(MultipartConfigElement.class);
		assertThat(multipart.getMaxFileSize()).isEqualTo(5L * 1024 * 1024);
		assertThat(multipart.getMaxRequestSize()).isEqualTo(6L * 1024 * 1024);
	}

	@Test
	void htmlResponsesAreGzipped() throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/login"))
			.header("Accept", "text/html")
			.header("Accept-Encoding", "gzip")
			.build();
		HttpResponse<byte[]> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofByteArray());
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("Content-Encoding")).hasValue("gzip");
	}

}
