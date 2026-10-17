package com.kosh.statutory;

import java.math.BigDecimal;

/**
 * Rule values with a fixed scale, so two versions with the same values are equal: rupees with 2 decimals, rates (per
 * cent) with 4, as the columns hold them. A value with more decimals is kept as it is, for the checks to refuse.
 */
final class Scales {

	static final int MONEY = 2;

	static final int RATE = 4;

	private Scales() {
	}

	static BigDecimal money(BigDecimal value) {
		return scaled(value, MONEY);
	}

	static BigDecimal rate(BigDecimal value) {
		return scaled(value, RATE);
	}

	private static BigDecimal scaled(BigDecimal value, int scale) {
		if (value == null) {
			return null;
		}
		BigDecimal plain = value.stripTrailingZeros();
		return (plain.scale() <= scale) ? plain.setScale(scale) : value;
	}

	static BigDecimal whole(int value) {
		return BigDecimal.valueOf(value);
	}

}
