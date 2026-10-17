package com.kosh.statutory;

/**
 * One single value of a rule family, as the rules page shows it and the new-version form asks for it. {@code min} and
 * {@code max} bound a {@link Kind#COUNT}; amounts are zero or more and rates 0-100 per cent.
 */
public record RuleField(String key, String label, Kind kind, String unit, int min, int max, String help) {

	public enum Kind {

		/** Rupees, up to two decimals. */
		MONEY,
		/** Per cent, up to four decimals. */
		RATE,
		/** A whole number of {@code unit}. */
		COUNT,
		/** Two six-month periods a year: the first one's starting month, 1-6 (4 is April and October). */
		HALF_YEARS

	}

	static RuleField money(String key, String label, String help) {
		return new RuleField(key, label, Kind.MONEY, null, 0, 0, help);
	}

	static RuleField rate(String key, String label, String help) {
		return new RuleField(key, label, Kind.RATE, null, 0, 0, help);
	}

	/** {@code unit} is plural ("years"); the page drops the final s for 1. */
	static RuleField count(String key, String label, String unit, int min, int max, String help) {
		return new RuleField(key, label, Kind.COUNT, unit, min, max, help);
	}

	static RuleField halfYears(String key, String label, String help) {
		return new RuleField(key, label, Kind.HALF_YEARS, null, 1, EsiRules.PERIOD_MONTHS, help);
	}

}
