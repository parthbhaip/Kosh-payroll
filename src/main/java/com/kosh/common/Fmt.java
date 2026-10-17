package com.kosh.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAccessor;
import java.util.Locale;

import org.springframework.stereotype.Component;

/**
 * Display formatting, available in templates as {@code @fmt} (for example {@code ${@fmt.money(line.amount)}}) and
 * injectable into PDF and e-mail code. Every method returns an em dash for a missing value, so templates need no null
 * checks. Display only: calculations round as PAYROLL-RULES.md says, never through these methods.
 */
@Component("fmt")
public class Fmt {

	/** Shown for a missing value. */
	public static final String NONE = "—";

	private static final String RUPEE = "₹";

	private static final BigDecimal LAKH = new BigDecimal("100000");

	private static final BigDecimal CRORE = new BigDecimal("10000000");

	private static final BigDecimal HUNDRED = new BigDecimal("100");

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

	private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH);

	private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

	private final Clock clock;

	public Fmt(Clock clock) {
		this.clock = clock;
	}

	/** {@code ₹1,23,456.50}: Indian digit grouping, two decimals (HALF_UP), a leading minus for negatives. */
	public String money(BigDecimal amount) {
		if (amount == null) {
			return NONE;
		}
		BigDecimal rounded = amount.setScale(2, RoundingMode.HALF_UP);
		return sign(rounded) + RUPEE + grouped(rounded.abs());
	}

	/** {@code 12%}, {@code 8.33%}, {@code 0.75%}: a rate in per cent, without trailing zeros. */
	public String percent(BigDecimal rate) {
		return (rate != null) ? rate.stripTrailingZeros().toPlainString() + "%" : NONE;
	}

	/**
	 * Short form for stat cards: {@code ₹45,000} below one lakh, then {@code ₹1.2 L} and {@code ₹3.4 Cr} with one
	 * decimal (dropped when it is zero, so {@code ₹1 L}).
	 */
	public String compactMoney(BigDecimal amount) {
		if (amount == null) {
			return NONE;
		}
		BigDecimal rupees = amount.setScale(0, RoundingMode.HALF_UP);
		BigDecimal abs = rupees.abs();
		String prefix = sign(rupees) + RUPEE;
		if (abs.compareTo(LAKH) < 0) {
			return prefix + grouped(abs);
		}
		BigDecimal lakhs = abs.divide(LAKH, 1, RoundingMode.HALF_UP);
		if (lakhs.compareTo(HUNDRED) < 0) {
			return prefix + grouped(oneDecimal(lakhs)) + " L";
		}
		return prefix + grouped(oneDecimal(abs.divide(CRORE, 1, RoundingMode.HALF_UP))) + " Cr";
	}

	/** {@code 30 Sep 2026}, from a {@code LocalDate} or {@code LocalDateTime}. */
	public String date(TemporalAccessor value) {
		return (value != null) ? DATE.format(value) : NONE;
	}

	/** {@code 30 Sep 2026, 2:05 PM}. */
	public String dateTime(TemporalAccessor value) {
		return (value != null) ? DATE_TIME.format(value) : NONE;
	}

	/** {@code September 2026}, from a {@code YearMonth} or a date. */
	public String month(TemporalAccessor value) {
		return (value != null) ? MONTH.format(value) : NONE;
	}

	/**
	 * {@code just now}, {@code 5 minutes ago}, {@code 3 hours ago}, {@code yesterday}, {@code 4 days ago} (and the
	 * same forwards: {@code in 2 hours}, {@code tomorrow}); a week or more away it is the date.
	 */
	public String relative(LocalDateTime when) {
		if (when == null) {
			return NONE;
		}
		LocalDateTime now = LocalDateTime.now(clock);
		boolean past = !when.isAfter(now);
		Duration gap = Duration.between(when, now).abs();
		if (gap.toMinutes() < 1) {
			return "just now";
		}
		if (gap.toHours() < 1) {
			return ago(gap.toMinutes(), "minute", past);
		}
		if (gap.toDays() < 1) {
			return ago(gap.toHours(), "hour", past);
		}
		long days = Math.abs(ChronoUnit.DAYS.between(when.toLocalDate(), now.toLocalDate()));
		if (days == 1) {
			return past ? "yesterday" : "tomorrow";
		}
		return (days < 7) ? ago(days, "day", past) : date(when);
	}

	/** {@code XXXXXX4321}. */
	public String maskedAccount(String number) {
		String masked = Masking.account(number);
		return (masked != null) ? masked : NONE;
	}

	/** {@code ABXXXXX4F}. */
	public String maskedPan(String pan) {
		String masked = Masking.pan(pan);
		return (masked != null) ? masked : NONE;
	}

	private static String sign(BigDecimal value) {
		return (value.signum() < 0) ? "-" : "";
	}

	private static BigDecimal oneDecimal(BigDecimal value) {
		return (value.remainder(BigDecimal.ONE).signum() == 0) ? value.setScale(0) : value;
	}

	/** Indian grouping of a non-negative number: the last three digits, then pairs ({@code 1,23,45,678.90}). */
	private static String grouped(BigDecimal value) {
		String plain = value.toPlainString();
		int dot = plain.indexOf('.');
		String digits = (dot < 0) ? plain : plain.substring(0, dot);
		String fraction = (dot < 0) ? "" : plain.substring(dot);
		StringBuilder out = new StringBuilder();
		int end = digits.length();
		int start = Math.max(0, end - 3);
		out.insert(0, digits, start, end);
		while (start > 0) {
			end = start;
			start = Math.max(0, end - 2);
			out.insert(0, ',').insert(0, digits, start, end);
		}
		return out.append(fraction).toString();
	}

	private static String ago(long count, String unit, boolean past) {
		String amount = count + " " + unit + ((count == 1) ? "" : "s");
		return past ? amount + " ago" : "in " + amount;
	}

}
