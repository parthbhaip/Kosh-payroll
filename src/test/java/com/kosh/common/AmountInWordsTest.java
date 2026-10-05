package com.kosh.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AmountInWordsTest {

	@Test
	void w12FortyFiveThousand() {
		assertThat(AmountInWords.of(new BigDecimal("45000"))).isEqualTo("Rupees Forty-Five Thousand Only");
	}

	@Test
	void w12OneLakhWithPaise() {
		assertThat(AmountInWords.of(new BigDecimal("123456.50")))
			.isEqualTo("Rupees One Lakh Twenty-Three Thousand Four Hundred Fifty-Six and Fifty Paise Only");
	}

	@Test
	void w12TwoCroreFiveLakh() {
		assertThat(AmountInWords.of(new BigDecimal("20500000"))).isEqualTo("Rupees Two Crore Five Lakh Only");
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
			"0       | Rupees Zero Only",
			"1       | Rupees One Only",
			"11      | Rupees Eleven Only",
			"19      | Rupees Nineteen Only",
			"20      | Rupees Twenty Only",
			"99      | Rupees Ninety-Nine Only",
			"100     | Rupees One Hundred Only",
			"101     | Rupees One Hundred One Only",
			"1000    | Rupees One Thousand Only",
			"10400   | Rupees Ten Thousand Four Hundred Only",
			"100000  | Rupees One Lakh Only",
			"999999  | Rupees Nine Lakh Ninety-Nine Thousand Nine Hundred Ninety-Nine Only",
			"10000000 | Rupees One Crore Only",
			"38000.00 | Rupees Thirty-Eight Thousand Only",
			"0.50    | Rupees Zero and Fifty Paise Only",
			"0.01    | Rupees Zero and One Paisa Only",
			"7.05    | Rupees Seven and Five Paise Only" })
	void indianSystem(String amount, String words) {
		assertThat(AmountInWords.of(new BigDecimal(amount))).isEqualTo(words);
	}

	@Test
	void croresAboveNinetyNineAreWordedInTheIndianSystemToo() {
		assertThat(AmountInWords.of(new BigDecimal("12345000000")))
			.isEqualTo("Rupees One Thousand Two Hundred Thirty-Four Crore Fifty Lakh Only");
	}

	@Test
	void paiseBeyondTwoDecimalsRoundHalfUp() {
		assertThat(AmountInWords.of(new BigDecimal("10.005"))).isEqualTo("Rupees Ten and One Paisa Only");
		assertThat(AmountInWords.of(new BigDecimal("99.995"))).isEqualTo("Rupees One Hundred Only");
	}

	@Test
	void negativeAmountsAreRejected() {
		assertThatThrownBy(() -> AmountInWords.of(new BigDecimal("-1"))).isInstanceOf(IllegalArgumentException.class);
	}

}
