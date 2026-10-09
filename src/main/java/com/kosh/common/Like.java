package com.kosh.common;

import java.util.Locale;

/**
 * Typed search text as a "contains" LIKE pattern for criteria queries. Compare it against a lower-cased column with
 * {@link #ESCAPE}, so {@code %}, {@code _} and the escape character in the text lose their meaning.
 */
public final class Like {

	/** Escape character for the patterns: not a backslash, which MySQL string literals treat as an escape. */
	public static final char ESCAPE = '!';

	private Like() {
	}

	/** {@code "50%_off"} becomes {@code "%50!%!_off%"}; the text is trimmed and lower-cased. */
	public static String containing(String text) {
		String literal = text.strip().toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_");
		return "%" + literal + "%";
	}

}
