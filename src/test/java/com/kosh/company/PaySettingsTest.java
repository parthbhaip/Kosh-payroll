package com.kosh.company;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.kosh.common.BusinessRuleException;
import com.kosh.statutory.PfPolicy;

/** Pay settings as a value (PLAN T11): the dates they give for a payroll month, the late pay-day flag, metro cities. */
class PaySettingsTest {

	/** The defaults migration V4 inserts. */
	private static final PaySettings DEFAULTS = new PaySettings(ProrationMethod.CALENDAR_DAYS, 7, 3, PfPolicy.RESTRICT,
			false, true, 30, CompanySettingsTest.METROS, 15, 15, 7, 31);

	@Test
	void septemberPayrollIsPaidAndRemittedInOctober() {
		YearMonth september = YearMonth.of(2026, 9);
		assertThat(DEFAULTS.attendanceCutoff(september)).isEqualTo(LocalDate.of(2026, 10, 3));
		assertThat(DEFAULTS.payDate(september)).isEqualTo(LocalDate.of(2026, 10, 7));
		assertThat(DEFAULTS.tdsDue(september)).isEqualTo(LocalDate.of(2026, 10, 7));
		assertThat(DEFAULTS.pfDue(september)).isEqualTo(LocalDate.of(2026, 10, 15));
		assertThat(DEFAULTS.esiDue(september)).isEqualTo(LocalDate.of(2026, 10, 15));
		assertThat(DEFAULTS.ptDue(september)).isEqualTo(LocalDate.of(2026, 10, 31));
	}

	@Test
	void aDueDayPastTheEndOfAShortMonthFallsOnItsLastDay() {
		PaySettings thirtieth = new PaySettings(ProrationMethod.CALENDAR_DAYS, 7, 3, PfPolicy.RESTRICT, false, true, 30,
				List.of("Delhi"), 30, 15, 7, 31);
		// January payroll: dues fall in February 2027, which has 28 days.
		assertThat(thirtieth.ptDue(YearMonth.of(2027, 1))).isEqualTo(LocalDate.of(2027, 2, 28));
		assertThat(thirtieth.pfDue(YearMonth.of(2027, 1))).isEqualTo(LocalDate.of(2027, 2, 28));
		// October payroll: November has 30 days, so "last day" is the 30th.
		assertThat(thirtieth.ptDue(YearMonth.of(2026, 10))).isEqualTo(LocalDate.of(2026, 11, 30));
		assertThat(thirtieth.pfDue(YearMonth.of(2026, 10))).isEqualTo(LocalDate.of(2026, 11, 30));
		// March payroll is paid in April of the next tax year.
		assertThat(thirtieth.payDate(YearMonth.of(2027, 3))).isEqualTo(LocalDate.of(2027, 4, 7));
	}

	@Test
	void aPayDayAfterTheSeventhIsLate() {
		assertThat(DEFAULTS.payDayIsLate()).isFalse();
		PaySettings eighth = new PaySettings(ProrationMethod.CALENDAR_DAYS, 8, 3, PfPolicy.RESTRICT, false, true, 30,
				List.of("Delhi"), 15, 15, 7, 31);
		assertThat(eighth.payDayIsLate()).isTrue();
	}

	@ParameterizedTest
	@CsvSource({ "1, 1st", "2, 2nd", "3, 3rd", "4, 4th", "11, 11th", "12, 12th", "13, 13th", "21, 21st", "22, 22nd",
			"23, 23rd", "28, 28th", "31, 31st" })
	void daysAreWrittenAsOrdinals(int day, String text) {
		assertThat(PaySettings.ordinal(day)).isEqualTo(text);
	}

	@Test
	void dueDayThirtyOneReadsAsTheLastDay() {
		assertThat(PaySettings.dueDayLabel(31)).isEqualTo("Last day of the month");
		assertThat(PaySettings.dueDayLabel(15)).isEqualTo("15th");
	}

	@Test
	void metroCitiesAreTidiedAndDuplicatesDropped() {
		assertThat(PaySettings.parseCities(" Delhi, Mumbai\nkolkata ,,  Navi   Mumbai , DELHI, St. Thomas' Mount\r\n"))
			.containsExactly("Delhi", "Mumbai", "kolkata", "Navi Mumbai", "St. Thomas' Mount");
	}

	@Test
	void anEmptyOrBadMetroListIsRefusedOnTheField() {
		assertThatThrownBy(() -> PaySettings.parseCities(" , \n "))
			.isInstanceOfSatisfying(BusinessRuleException.class,
					rule -> assertThat(rule.getField()).isEqualTo("metroCities"))
			.hasMessage("Enter at least one city.");
		assertThatThrownBy(() -> PaySettings.parseCities(null)).hasMessage("Enter at least one city.");
		assertThatThrownBy(() -> PaySettings.parseCities("Delhi, 400001"))
			.hasMessage("“400001” isn’t a city name. Use letters and spaces, for example Navi Mumbai.");
		assertThatThrownBy(() -> PaySettings.parseCities("Delhi; Mumbai")).hasMessageStartingWith("“Delhi; Mumbai”");
		String twentyOne = IntStream.rangeClosed(1, 21)
			.mapToObj(n -> "City " + (char) ('a' + n))
			.collect(Collectors.joining(", "));
		assertThatThrownBy(() -> PaySettings.parseCities(twentyOne)).hasMessage("List at most 20 cities.");
		// Typed text over 200 characters is fine when the list is short once duplicates are dropped.
		assertThat(PaySettings.parseCities("Thiruvananthapuram, ".repeat(11) + "Delhi"))
			.containsExactly("Thiruvananthapuram", "Delhi");
	}

	@Test
	void aListBuiltInCodeMeetsTheSameRules() {
		PaySettings.checkCities(CompanySettingsTest.METROS);
		assertThatThrownBy(() -> PaySettings.checkCities(List.of("Pune, Nashik")))
			.hasMessage("“Pune, Nashik” isn’t a city name. Use letters and spaces, for example Navi Mumbai.");
		assertThatThrownBy(() -> PaySettings.checkCities(List.of())).hasMessage("Enter at least one city.");
		List<String> long20 = IntStream.rangeClosed(1, 20).mapToObj(n -> "Thiruvananthapuram " + (char) ('a' + n)).toList();
		assertThatThrownBy(() -> PaySettings.checkCities(long20)).hasMessage("Keep the list within 200 characters.");
		assertThat(new PaySettings(ProrationMethod.CALENDAR_DAYS, 7, 3, PfPolicy.RESTRICT, false, true, 30, null, 15, 15,
				7, 31)
			.metroCities()).isEmpty();
	}

	@Test
	void theCityListCannotBeChangedFromOutside() {
		List<String> cities = new ArrayList<>(List.of("Delhi"));
		PaySettings settings = new PaySettings(ProrationMethod.CALENDAR_DAYS, 7, 3, PfPolicy.RESTRICT, false, true, 30,
				cities, 15, 15, 7, 31);
		cities.add("Pune");
		assertThat(settings.metroCities()).containsExactly("Delhi");
		assertThatThrownBy(() -> settings.metroCities().add("Pune")).isInstanceOf(UnsupportedOperationException.class);
	}

}
