package com.kosh.company;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

/**
 * The days a work location is closed every week, by their place in the calendar month: every Sunday, the 2nd and 4th
 * Saturday. The nth Saturday is the nth Saturday of the month, so the 2nd one always falls on the 8th to the 14th and a
 * 5th one only on the 29th to the 31st. Stored as text such as {@code "SATURDAY:2,4 SUNDAY"}: a day alone is off every
 * week, a day with weeks only in those weeks (1 to 5). Immutable; pure Java.
 */
public final class WeeklyOffs {

	/** A day can occur five times in a month. */
	public static final int WEEKS = 5;

	private static final int EVERY_WEEK = (1 << WEEKS) - 1;

	/** A location's work week until HR sets one: Sunday off. */
	public static final WeeklyOffs DEFAULT = parse("SUNDAY");

	/** By {@link DayOfWeek#ordinal()}; bit {@code n - 1} set = off in week {@code n}. */
	private final int[] masks;

	private WeeklyOffs(int[] masks) {
		this.masks = masks;
	}

	/**
	 * Reads the stored text, or the form's ticked boxes joined with spaces ({@code "SATURDAY:2 SATURDAY:4"}); weeks of
	 * the same day add up. Null or blank: no weekly offs.
	 * @throws IllegalArgumentException for an unknown day or a week outside 1 to 5
	 */
	public static WeeklyOffs parse(String text) {
		int[] masks = new int[7];
		if (text != null && !text.isBlank()) {
			for (String token : text.strip().split("\\s+")) {
				int colon = token.indexOf(':');
				DayOfWeek day = DayOfWeek.valueOf((colon < 0) ? token : token.substring(0, colon));
				masks[day.ordinal()] |= (colon < 0) ? EVERY_WEEK : weeks(token.substring(colon + 1));
			}
		}
		return new WeeklyOffs(masks);
	}

	/** Which occurrence of its weekday in the month a date is: 1 for the 1st to the 7th, 5 for the 29th to the 31st. */
	public static int weekOfMonth(LocalDate date) {
		return (date.getDayOfMonth() - 1) / 7 + 1;
	}

	public boolean isOff(LocalDate date) {
		return isOff(date.getDayOfWeek(), weekOfMonth(date));
	}

	/** @param week 1 to 5 */
	public boolean isOff(DayOfWeek day, int week) {
		return (masks[day.ordinal()] & (1 << (week - 1))) != 0;
	}

	/** True when no week of the month is all weekly offs, so every week has at least one working day. */
	public boolean hasWorkingDayEveryWeek() {
		int offEverywhere = EVERY_WEEK;
		for (int mask : masks) {
			offEverywhere &= mask;
		}
		return offEverywhere == 0;
	}

	/** The stored form: {@code "SATURDAY:2,4 SUNDAY"}, days in week order; empty for no weekly offs. */
	public String text() {
		StringJoiner text = new StringJoiner(" ");
		for (DayOfWeek day : DayOfWeek.values()) {
			int mask = masks[day.ordinal()];
			if (mask == EVERY_WEEK) {
				text.add(day.name());
			}
			else if (mask != 0) {
				StringJoiner weeks = new StringJoiner(",", day.name() + ":", "");
				weekList(mask).forEach(week -> weeks.add(String.valueOf(week)));
				text.add(weeks.toString());
			}
		}
		return text.toString();
	}

	/** "Off every Sunday and the 2nd and 4th Saturday", "Off every Saturday and Sunday", "No weekly offs". */
	public String describe() {
		List<String> everyWeek = new ArrayList<>();
		List<String> parts = new ArrayList<>();
		for (DayOfWeek day : DayOfWeek.values()) {
			int mask = masks[day.ordinal()];
			if (mask == EVERY_WEEK) {
				everyWeek.add(name(day));
			}
			else if (mask != 0) {
				parts.add("the " + ordinals(mask) + " " + name(day));
			}
		}
		if (!everyWeek.isEmpty()) {
			parts.addFirst("every " + joined(everyWeek));
		}
		return parts.isEmpty() ? "No weekly offs" : "Off " + joined(parts);
	}

	/** One day for the work week card: "Working day", "Off every week", "Off on the 2nd and 4th". */
	public String describe(DayOfWeek day) {
		int mask = masks[day.ordinal()];
		if (mask == 0) {
			return "Working day";
		}
		return (mask == EVERY_WEEK) ? "Off every week" : "Off on the " + ordinals(mask);
	}

	/** {@code Monday}. */
	public static String name(DayOfWeek day) {
		return day.getDisplayName(TextStyle.FULL, Locale.ENGLISH);
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof WeeklyOffs offs && Arrays.equals(masks, offs.masks);
	}

	@Override
	public int hashCode() {
		return Arrays.hashCode(masks);
	}

	@Override
	public String toString() {
		return text();
	}

	private static int weeks(String list) {
		int mask = 0;
		for (String week : list.split(",", -1)) {
			int n = Integer.parseInt(week);
			if (n < 1 || n > WEEKS) {
				throw new IllegalArgumentException("A month has weeks 1 to " + WEEKS + ", not " + n);
			}
			mask |= 1 << (n - 1);
		}
		return mask;
	}

	private static List<Integer> weekList(int mask) {
		List<Integer> weeks = new ArrayList<>();
		for (int week = 1; week <= WEEKS; week++) {
			if ((mask & (1 << (week - 1))) != 0) {
				weeks.add(week);
			}
		}
		return weeks;
	}

	private static String ordinals(int mask) {
		return joined(weekList(mask).stream().map(PaySettings::ordinal).toList());
	}

	/** "A", "A and B", "A, B and C". */
	private static String joined(List<String> items) {
		if (items.size() == 1) {
			return items.getFirst();
		}
		return String.join(", ", items.subList(0, items.size() - 1)) + " and " + items.getLast();
	}

}
