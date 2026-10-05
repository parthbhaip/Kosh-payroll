package com.kosh.common;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.kosh.config.AppProperties;

/**
 * Keyed look-up hashes (HMAC-SHA256, 64 lower-case hex characters) for encrypted values that must be unique or
 * searchable, such as PAN. The HMAC key is derived from the data key, so the AES key is never used for anything but
 * encryption. Callers normalise the value first (e.g. trim and upper-case a PAN): the hash is of the exact string.
 */
@Component
public class HmacHasher {

	private static final String ALGORITHM = "HmacSHA256";

	private static final byte[] PURPOSE = "kosh/lookup-hash/v1".getBytes(StandardCharsets.UTF_8);

	private final SecretKeySpec key;

	@Autowired
	public HmacHasher(AppProperties app) {
		this(app.dataKeyBytes());
	}

	HmacHasher(byte[] dataKey) {
		this.key = new SecretKeySpec(mac(new SecretKeySpec(dataKey, ALGORITHM), PURPOSE), ALGORITHM);
	}

	/** The look-up hash, or null for null. */
	public String hash(String value) {
		return (value != null) ? HexFormat.of().formatHex(mac(key, value.getBytes(StandardCharsets.UTF_8))) : null;
	}

	private static byte[] mac(SecretKeySpec key, byte[] data) {
		try {
			Mac mac = Mac.getInstance(ALGORITHM);
			mac.init(key);
			return mac.doFinal(data);
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("HmacSHA256 is not available", ex);
		}
	}

}
