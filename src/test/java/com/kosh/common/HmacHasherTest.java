package com.kosh.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

class HmacHasherTest {

	private final byte[] key = EncryptedStringConverterTest.randomKey();

	private final HmacHasher hasher = new HmacHasher(key);

	@Test
	void sameValueSameHash() {
		assertThat(hasher.hash("ABCDE1234F")).isEqualTo(new HmacHasher(key.clone()).hash("ABCDE1234F"))
			.matches("[0-9a-f]{64}");
	}

	@Test
	void differentValuesDifferentHashes() {
		assertThat(hasher.hash("ABCDE1234F")).isNotEqualTo(hasher.hash("ABCDE1234G"));
	}

	@Test
	void anotherKeyGivesAnotherHash() {
		assertThat(hasher.hash("ABCDE1234F"))
			.isNotEqualTo(new HmacHasher(EncryptedStringConverterTest.randomKey()).hash("ABCDE1234F"));
	}

	@Test
	void isNeitherAPlainDigestNorKeyedWithTheEncryptionKeyItself() throws Exception {
		String plainSha256 = HexFormat.of()
			.formatHex(MessageDigest.getInstance("SHA-256").digest("ABCDE1234F".getBytes(StandardCharsets.UTF_8)));
		Mac withDataKey = Mac.getInstance("HmacSHA256");
		withDataKey.init(new SecretKeySpec(key, "HmacSHA256"));
		String hmacWithDataKey = HexFormat.of()
			.formatHex(withDataKey.doFinal("ABCDE1234F".getBytes(StandardCharsets.UTF_8)));

		assertThat(hasher.hash("ABCDE1234F")).isNotEqualTo(plainSha256).isNotEqualTo(hmacWithDataKey);
	}

	@Test
	void nullStaysNull() {
		assertThat(hasher.hash(null)).isNull();
	}

}
