package com.kosh.common;

import java.util.regex.Pattern;

/**
 * Lines of an RFC 4180 CSV file for spreadsheet users: cells with a comma, quote or line break are quoted, and text
 * that a spreadsheet would run as a formula (starting with {@code = + - @}, tab or carriage return) gets a leading
 * apostrophe. Plain numbers such as {@code -1500.00} are left as they are.
 */
public final class Csv {

	/** Excel opens a UTF-8 file correctly (₹, names in Devanagari) only when it starts with this mark. */
	public static final String BOM = "﻿";

	private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?");

	private Csv() {
	}

	/** One line, ending in CRLF. A null cell is empty. */
	public static String line(Object... cells) {
		StringBuilder line = new StringBuilder();
		for (int i = 0; i < cells.length; i++) {
			if (i > 0) {
				line.append(',');
			}
			line.append(cell(cells[i]));
		}
		return line.append("\r\n").toString();
	}

	static String cell(Object value) {
		if (value == null) {
			return "";
		}
		String text = value.toString();
		if (!text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0 && !NUMBER.matcher(text).matches()) {
			text = "'" + text;
		}
		if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
			text = "\"" + text.replace("\"", "\"\"") + "\"";
		}
		return text;
	}

}
