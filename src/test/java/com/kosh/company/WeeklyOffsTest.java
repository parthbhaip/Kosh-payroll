package com.kosh.company;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Weekly offs by week of the month (PLAN T13), including the 2nd and 4th Saturday rule, and counting working days. */
class WeeklyOffsTest {

	/** Mon to Sat with the 2nd and 4th Saturday off: the factory pattern. */
	private static final WeeklyOffs FACTORY = WeeklyOffs.parse("SATURDAY:2,4 SUNDAY");

	@ParameterizedTest
	@CsvSource({ "2026-10-01, 1", "2026-10-07, 1", "2026-10-08, 2", "2026-10-14, 2", "2026-10-15, 3",
			"2026-10-22, 4", "2026-10-28, 4", "2026-10-29, 5", "2026-10-31, 5" })
	void theWeekOfTheMonthCountsSevenDaysFromTheFirst(LocalDate date, int week) {
		assertThat(WeeklyOffs.weekOfMonth(date)).isEqualTo(week);
	}

	@ParameterizedTest(name = "{0} is a weekly off: {1}")
	@CsvSource({
			// October 2026: Saturdays 3, 10, 17, 24, 31.
			"2026-10-03, false", "2026-10-10, true", "2026-10-17, false", "2026-10-24, true", "2026-10-31, false",
			// August 2026 starts on a Saturday: 1, 8, 15, 22, 29.
			"2026-08-01, false", "2026-08-08, true", "2026-08-15, false", "2026-08-22, true", "2026-08-29, false",
			// February 2026 has exactly four Saturdays: 7, 14, 21, 28.
			"2026-02-07, false", "2026-02-14, true", "2026-02-21, false", "2026-02-28, true",
			// Every Sunday; weekdays work.
			"2026-10-04, true", "2026-10-11, true", "2026-11-01, true", "2026-10-05, false", "2026-10-09, false" })
	void theSecondAndFourthSaturdaysAndEverySundayAreOff(LocalDate date, boolean off) {
		assertThat(FACTORY.isOff(date)).isEqualTo(off);
	}

	@Test
	void everyMonthHasOneSecondAndOneFourthSaturdayOff() {
		for (int month = 1; month <= 12; month++) {
			LocalDate first = LocalDate.of(2026, month, 1);
			long saturdaysOff = first.datesUntil(first.plusMonths(1))
				.filter(day -> day.getDayOfWeek() == DayOfWeek.SATURDAY && FACTORY.isOff(day))
				.count();
			assertThat(saturdaysOff).as("month %d", month).isEqualTo(2);
		}
	}

	@Test
	void theTextIsTidiedIntoWeekOrderAndReadBack() {
		WeeklyOffs ticked = WeeklyOffs.parse("SUNDAY:5 SATURDAY:4 SUNDAY:1 SUNDAY:2,3,4 SATURDAY:2");
		assertThat(ticked.text()).isEqualTo("SATURDAY:2,4 SUNDAY");
		assertThat(ticked).isEqualTo(FACTORY).hasSameHashCodeAs(FACTORY);
		assertThat(WeeklyOffs.parse(FACTORY.text())).isEqualTo(FACTORY);
		assertThat(WeeklyOffs.parse("SATURDAY:1,2,3,4,5").text()).isEqualTo("SATURDAY");
		assertThat(WeeklyOffs.parse("  ").text()).isEmpty();
		assertThat(WeeklyOffs.parse(null)).isEqualTo(WeeklyOffs.parse(""));
		assertThat(WeeklyOffs.DEFAULT.text()).isEqualTo("SUNDAY");
	}

	@ParameterizedTest
	@ValueSource(strings = { "FUNDAY", "saturday", "SATURDAY:0", "SATURDAY:6", "SATURDAY:", "SATURDAY:2,", "SAT:2" })
	void unknownDaysAndWeeksAreRefused(String text) {
		assertThatThrownBy(() -> WeeklyOffs.parse(text)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void theWorkWeekIsDescribedInWords() {
		assertThat(FACTORY.describe()).isEqualTo("Off every Sunday and the 2nd and 4th Saturday");
		assertThat(WeeklyOffs.parse("SATURDAY SUNDAY").describe()).isEqualTo("Off every Saturday and Sunday");
		assertThat(WeeklyOffs.parse("SATURDAY:1,3,5 SUNDAY").describe())
			.isEqualTo("Off every Sunday and the 1st, 3rd and 5th Saturday");
		assertThat(WeeklyOffs.parse("FRIDAY").describe()).isEqualTo("Off every Friday");
		assertThat(WeeklyOffs.parse("").describe()).isEqualTo("No weekly offs");
		assertThat(FACTORY.describe(DayOfWeek.MONDAY)).isEqualTo("Working day");
		assertThat(FACTORY.describe(DayOfWeek.SATURDAY)).isEqualTo("Off on the 2nd and 4th");
		assertThat(FACTORY.describe(DayOfWeek.SUNDAY)).isEqualTo("Off every week");
	}

	@Test
	void everyWeekOfTheMonthMustKeepAWorkingDay() {
		assertThat(FACTORY.hasWorkingDayEveryWeek()).isTrue();
		assertThat(WeeklyOffs.parse("").hasWorkingDayEveryWeek()).isTrue();
		assertThat(WeeklyOffs.parse("MONDAY TUESDAY WEDNESDAY THURSDAY FRIDAY SATURDAY").hasWorkingDayEveryWeek())
			.isTrue();
		assertThat(WeeklyOffs.parse("MONDAY TUESDAY WEDNESDAY THURSDAY FRIDAY SATURDAY SUNDAY").hasWorkingDayEveryWeek())
			.isFalse();
		// Weeks 2 and 4 would have no working day at all.
		assertThat(WeeklyOffs.parse("MONDAY TUESDAY WEDNESDAY THURSDAY FRIDAY SATURDAY SUNDAY:2,4")
			.hasWorkingDayEveryWeek()).isFalse();
	}

	@Test
	void workingDaysSkipWeeklyOffsAndClosedDaysOnce() {
		LocalDate first = LocalDate.of(2026, 10, 1);
		LocalDate last = LocalDate.of(2026, 10, 31);
		// 31 days - 4 Sundays - 2 Saturdays (10th, 24th).
		assertThat(HolidayService.workingDays(FACTORY, Set.of(), first, last)).isEqualTo(25);
		// Gandhi Jayanti (Friday 2nd) and Dussehra (Tuesday 20th).
		Set<LocalDate> closed = Set.of(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 20));
		assertThat(HolidayService.workingDays(FACTORY, closed, first, last)).isEqualTo(23);
		// A holiday on a weekly off is not taken off twice.
		assertThat(HolidayService.workingDays(FACTORY, Set.of(LocalDate.of(2026, 10, 11)), first, last)).isEqualTo(25);
		// One day: the 2nd Saturday is off, the 3rd works.
		assertThat(HolidayService.workingDays(FACTORY, Set.of(), LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 10)))
			.isZero();
		assertThat(HolidayService.workingDays(FACTORY, Set.of(), LocalDate.of(2026, 10, 17), LocalDate.of(2026, 10, 17)))
			.isEqualTo(1);
		// 2026 has 52 Sundays and 24 2nd or 4th Saturdays.
		assertThat(HolidayService.workingDays(FACTORY, Set.of(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)))
			.isEqualTo(365 - 52 - 24);
	}

}
