package com.kosh.config;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.Profiles;

/**
 * Tests never use a fixed data key (CLAUDE.md > Sensitive data): under the {@code test} profile, one random AES-256 key
 * is generated per test JVM and shared by every cached context, so data encrypted by one context stays readable in the
 * shared in-memory database. The key goes in as the first property source: Spring maps an exported {@code APP_DATA_KEY}
 * onto {@code app.data-key}, and a key from the developer's shell must never reach a test. Registered in
 * {@code src/test/resources/META-INF/spring.factories}.
 */
public class TestDataKeyEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

	static final String PROPERTY_SOURCE = "generatedTestDataKey";

	private static final String KEY = generateKey();

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		if (environment.acceptsProfiles(Profiles.of("test"))) {
			environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE, Map.of("app.data-key", KEY)));
		}
	}

	/** After config data, so profiles activated in property files are already known. */
	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE;
	}

	/** A fresh random key on every call: base64 of {@link AppProperties#DATA_KEY_BYTES} bytes. */
	static String generateKey() {
		byte[] key = new byte[AppProperties.DATA_KEY_BYTES];
		new SecureRandom().nextBytes(key);
		return Base64.getEncoder().encodeToString(key);
	}

}
