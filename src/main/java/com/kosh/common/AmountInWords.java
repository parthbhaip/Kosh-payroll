package com.kosh.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Rupee amounts in words in the Indian system (thousand, lakh, crore), as printed on payslips and settlement letters:
 * {@code 1,23,456.50} is "Rupees One Lakh Twenty-Three Thousand Four Hundred Fifty-Six and Fifty Paise Only".
 * Golden values: PAYROLL-RULES.md W12.
 */
public final class AmountInWords {

	private static final String[] UNITS = { "Zero", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight",
			"Nine", "Ten", "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen", "Eighteen",
			"Nineteen" };

	private static final String[] TENS = { "", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty",
			"Ninety" };

	private AmountInWords() {
	}

	/**
	 * @param amount zero or more; paise beyond two decimals are rounded HALF_UP
	 * @throws IllegalArgumentException for a negative amount, which has no wording on a payslip
	 */
	public static String of(BigDecimal amount) {
		Objects.requireNonNull(amount, "amount");
		if (amount.signum() < 0) {
			throw new IllegalArgumentException("Amount in words needs an amount of zero or more");
		}
		BigDecimal rounded = amount.setScale(2, RoundingMode.HALF_UP);
		long rupees = rounded.setScale(0, RoundingMode.DOWN).longValueExact();
		int paise = rounded.remainder(BigDecimal.ONE).movePointRight(2).intValueExact();
		StringBuilder words = new StringBuilder("Rupees ").append(words(rupees));
		if (paise > 0) {
			words.append(" and ").append(belowHundred(paise)).append((paise == 1) ? " Paisa" : " Paise");
		}
		return words.append(" Only").toString();
	}

	private static String words(long number) {
		if (number == 0) {
			return UNITS[0];
		}
		List<String> parts = new ArrayList<>();
		long crores = number / 1_00_00_000;
		long lakhs = number / 1_00_000 % 100;
		long thousands = number / 1_000 % 100;
		long hundreds = number / 100 % 10;
		long rest = number % 100;
		if (crores > 0) {
			parts.add(words(crores) + " Crore");
		}
		if (lakhs > 0) {
			parts.add(belowHundred(lakhs) + " Lakh");
		}
		if (thousands > 0) {
			parts.add(belowHundred(thousands) + " Thousand");
		}
		if (hundreds > 0) {
			parts.add(UNITS[(int) hundreds] + " Hundred");
		}
		if (rest > 0) {
			parts.add(belowHundred(rest));
		}
		return String.join(" ", parts);
	}

	private static String belowHundred(long number) {
		int n = (int) number;
		if (n < 20) {
			return UNITS[n];
		}
		return TENS[n / 10] + ((n % 10 > 0) ? "-" + UNITS[n % 10] : "");
	}

}
