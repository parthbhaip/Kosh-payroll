package com.kosh.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EncryptedStringConverterTest {

	private final EncryptedStringConverter converter = new EncryptedStringConverter(randomKey());

	@ParameterizedTest
	@ValueSource(strings = { "ABCDE1234F", "50100234564321", "", "Kolhapur ₹ कोल्हापूर" })
	void roundTrip(String plain) {
		String stored = converter.convertToDatabaseColumn(plain);
		assertThat(stored).isNotEqualTo(plain);
		assertThat(converter.convertToEntityAttribute(stored)).isEqualTo(plain);
	}

	@Test
	void storedFormIsBase64OfIvCiphertextAndTag() {
		String stored = converter.convertToDatabaseColumn("ABCDE1234F");
		assertThat(Base64.getDecoder().decode(stored)).hasSize(12 + 10 + 16);
		assertThat(stored).hasSize(52).doesNotContain("ABCDE1234F");
		assertThat(converter.convertToDatabaseColumn("123456789012345678")).hasSize(64);
	}

	@Test
	void everyValueGetsAFreshIv() {
		String first = converter.convertToDatabaseColumn("ABCDE1234F");
		String second = converter.convertToDatabaseColumn("ABCDE1234F");
		assertThat(first).isNotEqualTo(second);
		assertThat(converter.convertToEntityAttribute(first)).isEqualTo(converter.convertToEntityAttribute(second));
	}

	@Test
	void nullStaysNull() {
		assertThat(converter.convertToDatabaseColumn(null)).isNull();
		assertThat(converter.convertToEntityAttribute(null)).isNull();
	}

	@Test
	void anyChangedByteIsDetected() {
		byte[] sealed = Base64.getDecoder().decode(converter.convertToDatabaseColumn("ABCDE1234F"));
		for (int i = 0; i < sealed.length; i++) {
			byte[] tampered = sealed.clone();
			tampered[i] ^= 0x01;
			String stored = Base64.getEncoder().encodeToString(tampered);
			assertThatThrownBy(() -> converter.convertToEntityAttribute(stored))
				.as("flipped bit in byte %d", i)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("integrity check")
				.hasMessageNotContaining(stored);
		}
	}

	@Test
	void truncatedOrGarbledValuesAreRejected() {
		String stored = converter.convertToDatabaseColumn("ABCDE1234F");
		assertThatThrownBy(() -> converter.convertToEntityAttribute(stored.substring(0, 20)))
			.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> converter.convertToEntityAttribute("ABCDE1234F"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageNotContaining("ABCDE1234F");
		assertThatThrownBy(() -> converter.convertToEntityAttribute("not base64 at all!"))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void anotherKeyCannotReadTheValue() {
		String stored = converter.convertToDatabaseColumn("ABCDE1234F");
		EncryptedStringConverter other = new EncryptedStringConverter(randomKey());
		assertThatThrownBy(() -> other.convertToEntityAttribute(stored)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("APP_DATA_KEY");
	}

	@Test
	void keyMustBe256Bits() {
		assertThatThrownBy(() -> new EncryptedStringConverter(new byte[16])).isInstanceOf(IllegalArgumentException.class);
	}

	static byte[] randomKey() {
		byte[] key = new byte[32];
		new SecureRandom().nextBytes(key);
		return key;
	}

}
