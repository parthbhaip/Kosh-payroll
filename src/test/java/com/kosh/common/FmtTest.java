package com.kosh.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FmtTest {

	/** 5 Oct 2026, 10:00 in Kolkata. */
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 5, 10, 0);

	private final Fmt fmt = new Fmt(Clock.fixed(NOW.atZone(ZoneId.of("Asia/Kolkata")).toInstant(), ZoneId.of("Asia/Kolkata")));

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
			"0            | ₹0.00",
			"5            | ₹5.00",
			"999.5        | ₹999.50",
			"1000         | ₹1,000.00",
			"38000        | ₹38,000.00",
			"123456.50    | ₹1,23,456.50",
			"1234567.89   | ₹12,34,567.89",
			"20500000     | ₹2,05,00,000.00",
			"123456789012 | ₹1,23,45,67,89,012.00",
			"10.005       | ₹10.01",
			"-1500        | -₹1,500.00",
			"-123456.5    | -₹1,23,456.50",
			"-0.004       | ₹0.00" })
	void moneyUsesIndianGroupingAndTwoDecimals(String amount, String shown) {
		assertThat(fmt.money(new BigDecimal(amount))).isEqualTo(shown);
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
			"0           | ₹0",
			"45000.40    | ₹45,000",
			"99999.49    | ₹99,999",
			"99999.50    | ₹1 L",
			"120000      | ₹1.2 L",
			"125000      | ₹1.3 L",
			"1000000     | ₹10 L",
			"9999999     | ₹1 Cr",
			"34000000    | ₹3.4 Cr",
			"20500000    | ₹2.1 Cr",
			"12345000000 | ₹1,234.5 Cr",
			"-120000     | -₹1.2 L" })
	void compactMoneyUsesLakhAndCrore(String amount, String shown) {
		assertThat(fmt.compactMoney(new BigDecimal(amount))).isEqualTo(shown);
	}

	@Test
	void datesAndMonths() {
		assertThat(fmt.date(LocalDate.of(2026, 9, 30))).isEqualTo("30 Sep 2026");
		assertThat(fmt.date(LocalDate.of(2026, 10, 5))).isEqualTo("5 Oct 2026");
		assertThat(fmt.date(LocalDateTime.of(2026, 9, 30, 18, 45))).isEqualTo("30 Sep 2026");
		assertThat(fmt.dateTime(LocalDateTime.of(2026, 9, 30, 14, 5))).isEqualTo("30 Sep 2026, 2:05 PM");
		assertThat(fmt.month(YearMonth.of(2026, 9))).isEqualTo("September 2026");
		assertThat(fmt.month(LocalDate.of(2027, 3, 31))).isEqualTo("March 2027");
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
			"2026-10-05T10:00:00 | just now",
			"2026-10-05T09:59:01 | just now",
			"2026-10-05T09:59:00 | 1 minute ago",
			"2026-10-05T09:55:00 | 5 minutes ago",
			"2026-10-05T09:00:00 | 1 hour ago",
			"2026-10-05T07:00:00 | 3 hours ago",
			"2026-10-04T10:30:00 | 23 hours ago",
			"2026-10-04T09:00:00 | yesterday",
			"2026-10-03T11:00:00 | 2 days ago",
			"2026-09-29T08:00:00 | 6 days ago",
			"2026-09-28T10:00:00 | 28 Sep 2026",
			"2026-10-05T10:05:00 | in 5 minutes",
			"2026-10-05T12:00:00 | in 2 hours",
			"2026-10-06T18:00:00 | tomorrow",
			"2026-10-09T09:00:00 | in 4 days",
			"2026-12-25T00:00:00 | 25 Dec 2026" })
	void relativeTimeAgainstTheClock(String when, String shown) {
		assertThat(fmt.relative(LocalDateTime.parse(when))).isEqualTo(shown);
	}

	@Test
	void masksBankAccountsToTheLastFourDigits() {
		assertThat(fmt.maskedAccount("50100234564321")).isEqualTo("XXXXXX4321");
		assertThat(fmt.maskedAccount("123456789")).isEqualTo("XXXXXX6789");
		assertThat(fmt.maskedAccount(" 004321 ")).isEqualTo("XXXXXX4321");
		assertThat(fmt.maskedAccount("4321")).isEqualTo("XXXXXX");
	}

	@Test
	void masksPanToFirstTwoAndLastTwo() {
		assertThat(fmt.maskedPan("ABCDE1234F")).isEqualTo("ABXXXXX4F");
		assertThat(Masking.pan("ABCDE1234F")).isEqualTo("ABXXXXX4F");
		assertThat(fmt.maskedPan("ABC12")).isEqualTo("XXXXXXXXX");
	}

	@Test
	void missingValuesShowAnEmDash() {
		assertThat(fmt.money(null)).isEqualTo("—");
		assertThat(fmt.compactMoney(null)).isEqualTo("—");
		assertThat(fmt.date(null)).isEqualTo("—");
		assertThat(fmt.dateTime(null)).isEqualTo("—");
		assertThat(fmt.month(null)).isEqualTo("—");
		assertThat(fmt.relative(null)).isEqualTo("—");
		assertThat(fmt.maskedAccount(null)).isEqualTo("—");
		assertThat(fmt.maskedAccount("  ")).isEqualTo("—");
		assertThat(fmt.maskedPan(null)).isEqualTo("—");
		assertThat(Masking.account(null)).isNull();
		assertThat(Masking.pan("")).isNull();
	}

}
