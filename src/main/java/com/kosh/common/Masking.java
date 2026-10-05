package com.kosh.common;

/**
 * Masks for sensitive identifiers wherever they are shown, logged or audited. The masks have a fixed width, so they
 * never reveal the length of the original value.
 */
public final class Masking {

	private static final String ACCOUNT_MASK = "XXXXXX";

	private static final String PAN_MASK = "XXXXX";

	private Masking() {
	}

	/** {@code 001234567894321} becomes {@code XXXXXX4321}; a value of four characters or fewer is masked whole. */
	public static String account(String number) {
		if (number == null || number.isBlank()) {
			return null;
		}
		String digits = number.strip();
		return (digits.length() <= 4) ? ACCOUNT_MASK : ACCOUNT_MASK + digits.substring(digits.length() - 4);
	}

	/** {@code ABCDE1234F} becomes {@code ABXXXXX4F}; anything that is not a 10-character PAN is masked whole. */
	public static String pan(String pan) {
		if (pan == null || pan.isBlank()) {
			return null;
		}
		String value = pan.strip();
		return (value.length() != 10) ? PAN_MASK + "XXXX" : value.substring(0, 2) + PAN_MASK + value.substring(8);
	}

}
