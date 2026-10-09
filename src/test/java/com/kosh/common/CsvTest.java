package com.kosh.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class CsvTest {

	@Test
	void cellsAreJoinedByCommasAndTheLineEndsInCrLf() {
		assertThat(Csv.line("Meera Deshpande", 12L, null, new BigDecimal("1500.00"))).isEqualTo("Meera Deshpande,12,,1500.00\r\n");
	}

	@Test
	void commasQuotesAndLineBreaksAreQuoted() {
		assertThat(Csv.line("Deshpande, Meera", "Said \"hi\"", "two\nlines", "cr\rhere"))
			.isEqualTo("\"Deshpande, Meera\",\"Said \"\"hi\"\"\",\"two\nlines\",\"cr\rhere\"\r\n");
	}

	@Test
	void textThatASpreadsheetWouldRunAsAFormulaGetsAnApostrophe() {
		assertThat(Csv.cell("=HYPERLINK(\"http://evil.test\")")).isEqualTo("\"'=HYPERLINK(\"\"http://evil.test\"\")\"");
		assertThat(Csv.cell("+91 98220 12345")).isEqualTo("'+91 98220 12345");
		assertThat(Csv.cell("-cmd")).isEqualTo("'-cmd");
		assertThat(Csv.cell("@SUM(A1)")).isEqualTo("'@SUM(A1)");
		assertThat(Csv.cell("\tTab")).isEqualTo("'\tTab");
		assertThat(Csv.cell("Signed in")).isEqualTo("Signed in");
	}

	@Test
	void plainNumbersKeepTheirSign() {
		assertThat(Csv.cell("-1500.00")).isEqualTo("-1500.00");
		assertThat(Csv.cell("-42")).isEqualTo("-42");
		assertThat(Csv.cell("")).isEmpty();
	}

}
