package com.kosh.common;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import org.springframework.beans.factory.annotation.Autowired;

import com.kosh.config.AppProperties;

/**
 * Encrypts PAN and bank account numbers at rest: {@code @Convert(converter = EncryptedStringConverter.class)}.
 * AES-256-GCM with a fresh random 12-byte IV per value; the column holds base64 of IV + ciphertext + 16-byte tag, so a
 * value of n UTF-8 bytes needs {@code 4 * ceil((n + 28) / 3)} characters (a 10-character PAN takes 52, an 18-digit
 * account number 64). Hibernate gets this converter from Spring, which injects the key. Equal values encrypt
 * differently, so look-ups and uniqueness go through {@link HmacHasher}.
 */
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

	private static final String TRANSFORMATION = "AES/GCM/NoPadding";

	private static final int IV_BYTES = 12;

	private static final int TAG_BITS = 128;

	private final SecretKey key;

	private final SecureRandom random = new SecureRandom();

	@Autowired
	public EncryptedStringConverter(AppProperties app) {
		this(app.dataKeyBytes());
	}

	EncryptedStringConverter(byte[] key) {
		if (key.length != 32) {
			throw new IllegalArgumentException("The data key must be 32 bytes (AES-256)");
		}
		this.key = new SecretKeySpec(key, "AES");
	}

	@Override
	public String convertToDatabaseColumn(String plain) {
		if (plain == null) {
			return null;
		}
		byte[] iv = new byte[IV_BYTES];
		random.nextBytes(iv);
		try {
			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
			byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
			return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array());
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("Could not encrypt a value", ex);
		}
	}

	/**
	 * @throws IllegalStateException when the stored value was altered or encrypted with another key; the message never
	 * contains the stored value
	 */
	@Override
	public String convertToEntityAttribute(String stored) {
		if (stored == null) {
			return null;
		}
		byte[] bytes;
		try {
			bytes = Base64.getDecoder().decode(stored);
		}
		catch (IllegalArgumentException notBase64) {
			throw tampered(null);
		}
		if (bytes.length < IV_BYTES + TAG_BITS / 8) {
			throw tampered(null);
		}
		try {
			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES));
			return new String(cipher.doFinal(bytes, IV_BYTES, bytes.length - IV_BYTES), StandardCharsets.UTF_8);
		}
		catch (AEADBadTagException ex) {
			throw tampered(ex);
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("Could not decrypt a value", ex);
		}
	}

	private static IllegalStateException tampered(Exception cause) {
		return new IllegalStateException(
				"An encrypted value failed its integrity check: it was altered, or encrypted with a different APP_DATA_KEY",
				cause);
	}

}
