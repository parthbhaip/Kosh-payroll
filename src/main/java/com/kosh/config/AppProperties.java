package com.kosh.config;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.Base64;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Application settings under {@code app.*}. Validated at startup, so a bad key or URL stops the app before it serves a
 * request.
 *
 * @param brand product name shown in the UI and e-mails
 * @param zone business time zone for the {@code Clock} bean and every date shown
 * @param uploadDir where uploaded documents are stored, outside the classpath
 * @param seedDemo load the fictional demo company on startup
 * @param dataKey base64 of the 32-byte AES-256 key that encrypts PAN and bank details; never logged
 * @param baseUrl absolute address used to build links in e-mails, without a trailing slash
 * @param mail outgoing mail settings
 */
@Validated
@ConfigurationProperties("app")
public record AppProperties(
		@NotBlank String brand,
		@NotNull ZoneId zone,
		@NotNull Path uploadDir,
		boolean seedDemo,
		@NotBlank(message = "must be set; the mysql profile reads it from APP_DATA_KEY") String dataKey,
		@NotBlank @Pattern(regexp = "https?://[^\\s/]+(/\\S*)?(?<!/)",
				message = "must be an absolute http(s) URL without a trailing slash, e.g. https://payroll.example.in") String baseUrl,
		@NotNull @Valid Mail mail) {

	static final int DATA_KEY_BYTES = 32;

	/**
	 * True when the data key is base64 for exactly 32 bytes. Blank keys are reported by {@code @NotBlank}. A missing
	 * {@code APP_DATA_KEY} reaches this check as the literal {@code ${APP_DATA_KEY}}, because the binder leaves
	 * unresolved placeholders in place, so the message names the variable.
	 */
	@AssertTrue(message = "app.data-key must be base64 of exactly 32 random bytes (AES-256); the mysql profile reads it from APP_DATA_KEY")
	public boolean isDataKeyValid() {
		if (dataKey == null || dataKey.isBlank()) {
			return true;
		}
		try {
			return Base64.getDecoder().decode(dataKey).length == DATA_KEY_BYTES;
		}
		catch (IllegalArgumentException notBase64) {
			return false;
		}
	}

	/** The decoded AES key; a fresh copy on every call. */
	public byte[] dataKeyBytes() {
		return Base64.getDecoder().decode(dataKey);
	}

	/** Keeps the data key out of logs and error messages. */
	@Override
	public String toString() {
		return "AppProperties[brand=%s, zone=%s, uploadDir=%s, seedDemo=%s, dataKey=[hidden], baseUrl=%s, mail=%s]"
			.formatted(brand, zone, uploadDir, seedDemo, baseUrl, mail);
	}

	/**
	 * @param transport LOG writes e-mails to the application log (dev, test); SMTP sends them (mysql)
	 * @param from sender address on every e-mail
	 */
	public record Mail(@NotNull Transport transport, @NotBlank @Email String from) {
	}

	public enum Transport {

		LOG, SMTP

	}

}
