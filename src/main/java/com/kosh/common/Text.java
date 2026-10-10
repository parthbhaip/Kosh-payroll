package com.kosh.common;

import java.util.Locale;

/** Tidying typed text before it is checked and stored. */
public final class Text {

	private Text() {
	}

	/** Blank becomes null; otherwise trimmed with inner runs of white space made single. */
	public static String clean(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		return value.strip().replaceAll("\\s+", " ");
	}

	/** Codes and registration numbers: spaces removed and upper case, so "abcde 1234 f" is ABCDE1234F. */
	public static String code(String value) {
		String text = clean(value);
		return (text == null) ? null : text.replace(" ", "").toUpperCase(Locale.ROOT);
	}

}
