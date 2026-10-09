package com.kosh.company;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.kosh.common.BusinessRuleException;
import com.kosh.statutory.PfPolicy;

/**
 * The company's pay settings as one immutable value, for the settings page and for payroll code. Days are days of the
 * month after the pay period: payroll for September is paid on {@code payDay} October. A due day past the end of a
 * short month falls on its last day, so 31 means "the last day of the month".
 *
 * @param prorationMethod how prorated components are divided (PAYROLL-RULES section 2)
 * @param payDay day salaries are paid, 2 to 28 (the cut-off must fall before it)
 * @param attendanceCutoffDay day the month's attendance is closed, 1 to 27 and before {@code payDay}
 * @param defaultPfPolicy PF policy for new employees: RESTRICT or ACTUAL
 * @param gratuityInCtc add the gratuity provision to CTC (PAYROLL-RULES section 4)
 * @param pfChargesInCtc add PF admin charges and EDLI to CTC (PAYROLL-RULES section 15)
 * @param leaveEncashmentDivisor days that (Basic + DA) is divided by for leave encashment (section 13), 20 to 31
 * @param metroCities cities where the HRA exemption uses 50% of (Basic + DA) under the old regime (section 8.2), at
 * most {@value #MAX_CITIES_TEXT} characters when joined with commas
 * @param pfDueDay PF remittance due day, 1 to 31
 * @param esiDueDay ESI remittance due day
 * @param tdsDueDay TDS deposit due day
 * @param ptDueDay professional tax due day
 */
public record PaySettings(ProrationMethod prorationMethod, int payDay, int attendanceCutoffDay,
		PfPolicy defaultPfPolicy, boolean gratuityInCtc, boolean pfChargesInCtc, int leaveEncashmentDivisor,
		List<String> metroCities, int pfDueDay, int esiDueDay, int tdsDueDay, int ptDueDay) {

	/** Wages are due by the 7th of the following month (PAYROLL-RULES section 2); a later pay day gets a warning. */
	public static final int LATEST_PAY_DAY = 7;

	/** A due day of 31 is shown as "Last day of the month". */
	public static final int LAST_DAY = 31;

	static final int FIRST_PAY_DAY = 2;

	static final int LAST_PAY_DAY = 28;

	static final int LAST_CUTOFF_DAY = 27;

	static final int MIN_DIVISOR = 20;

	static final int MAX_DIVISOR = 31;

	static final int MAX_CITIES = 20;

	/** The joined list ("Delhi, Mumbai") is kept whole in the audit log, so it stays within an audit value. */
	static final int MAX_CITIES_TEXT = 200;

	static final String PAY_DAY_RANGE = "Choose a pay day from the 2nd to the 28th.";

	static final String CUTOFF_RANGE = "Choose a cut-off from the 1st to the 27th.";

	static final String DIVISOR_RANGE = "Use a divisor from 20 to 31. Most companies use 30 or 26.";

	static final String DUE_DAY_RANGE = "Choose a day of the month.";

	private static final Pattern CITY = Pattern.compile("\\p{L}[\\p{L} .'-]{1,39}");

	public PaySettings {
		// A missing list becomes empty, so the service refuses it as "Enter at least one city."
		metroCities = (metroCities == null) ? List.of() : List.copyOf(metroCities);
	}

	/** True when salaries are paid after the 7th of the following month. */
	public boolean payDayIsLate() {
		return payDay > LATEST_PAY_DAY;
	}

	/** The day salaries for {@code month} are paid. */
	public LocalDate payDate(YearMonth month) {
		return month.plusMonths(1).atDay(payDay);
	}

	/** The day attendance for {@code month} must be closed. */
	public LocalDate attendanceCutoff(YearMonth month) {
		return month.plusMonths(1).atDay(attendanceCutoffDay);
	}

	public LocalDate pfDue(YearMonth month) {
		return due(month, pfDueDay);
	}

	public LocalDate esiDue(YearMonth month) {
		return due(month, esiDueDay);
	}

	public LocalDate tdsDue(YearMonth month) {
		return due(month, tdsDueDay);
	}

	public LocalDate ptDue(YearMonth month) {
		return due(month, ptDueDay);
	}

	/** {@code 1st}, {@code 2nd}, {@code 23rd}, {@code 28th}. */
	public static String ordinal(int day) {
		int lastTwo = day % 100;
		String suffix = switch ((lastTwo >= 11 && lastTwo <= 13) ? 0 : day % 10) {
			case 1 -> "st";
			case 2 -> "nd";
			case 3 -> "rd";
			default -> "th";
		};
		return day + suffix;
	}

	/** A due day as the settings page shows it: {@code 15th}, or {@code Last day of the month} for 31. */
	public static String dueDayLabel(int day) {
		return (day == LAST_DAY) ? "Last day of the month" : ordinal(day);
	}

	/**
	 * The metro list typed on the settings page: names separated by commas or new lines, tidied, duplicates dropped
	 * (ignoring case), in the order typed.
	 * @throws BusinessRuleException (field {@code metroCities}) as {@link #checkCities} does
	 */
	public static List<String> parseCities(String text) {
		List<String> cities = new ArrayList<>();
		for (String part : (text == null) ? new String[0] : text.split("[,\\n]")) {
			String city = part.strip().replaceAll("\\s+", " ");
			if (!city.isEmpty() && cities.stream().noneMatch(city::equalsIgnoreCase)) {
				cities.add(city);
			}
		}
		checkCities(cities);
		return cities;
	}

	/**
	 * The rules for a metro list, whoever builds it: every entry one city name (so a stored "Pune, Nashik" can never
	 * read back as two), 1 to {@value #MAX_CITIES} cities, at most {@value #MAX_CITIES_TEXT} characters joined.
	 * @throws BusinessRuleException (field {@code metroCities}) when a rule fails
	 */
	static void checkCities(List<String> cities) {
		for (String city : cities) {
			if (!CITY.matcher(city).matches()) {
				throw new BusinessRuleException("metroCities",
						"“" + abbreviate(city) + "” isn’t a city name. Use letters and spaces, for example Navi Mumbai.");
			}
		}
		if (cities.isEmpty()) {
			throw new BusinessRuleException("metroCities", "Enter at least one city.");
		}
		if (cities.size() > MAX_CITIES) {
			throw new BusinessRuleException("metroCities", "List at most " + MAX_CITIES + " cities.");
		}
		if (String.join(", ", cities).length() > MAX_CITIES_TEXT) {
			throw new BusinessRuleException("metroCities", "Keep the list within " + MAX_CITIES_TEXT + " characters.");
		}
	}

	private static LocalDate due(YearMonth month, int day) {
		YearMonth next = month.plusMonths(1);
		return next.atDay(Math.min(day, next.lengthOfMonth()));
	}

	private static String abbreviate(String text) {
		return (text.length() <= 40) ? text : text.substring(0, 39) + "…";
	}

}
