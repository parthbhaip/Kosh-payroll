package com.kosh.user;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.kosh.common.BusinessRuleException;

/** New passwords: 8 to 64 characters, at most 72 bytes (BCrypt's limit), no composition rules. */
class PasswordRulesTest {

	@Test
	void eightToSixtyFourCharactersAreAccepted() {
		assertThatCode(() -> PasswordLinkService.checkPassword("a".repeat(8))).doesNotThrowAnyException();
		assertThatCode(() -> PasswordLinkService.checkPassword("a".repeat(64))).doesNotThrowAnyException();
		assertThatCode(() -> PasswordLinkService.checkPassword("tea at four on the shiroli lawn")).doesNotThrowAnyException();
	}

	@Test
	void tooShortTooLongOrEmptyIsRefusedOnThePasswordField() {
		expect("a".repeat(7), "Use 8 to 64 characters.");
		expect("a".repeat(65), "Use 8 to 64 characters.");
		expect("", "Enter a password.");
		expect(null, "Enter a password.");
	}

	@Test
	void charactersAreCountedAsTypedAndBytesAgainstBcryptsLimit() {
		// 24 rupee signs: 24 characters, 72 bytes in UTF-8 - exactly the limit.
		assertThatCode(() -> PasswordLinkService.checkPassword("₹".repeat(24))).doesNotThrowAnyException();
		expect("₹".repeat(25), "That password is too long for these characters. Use a shorter one.");
		// An emoji is two Java chars but one character.
		assertThatCode(() -> PasswordLinkService.checkPassword("😀".repeat(8))).doesNotThrowAnyException();
		expect("😀".repeat(7), "Use 8 to 64 characters.");
	}

	private static void expect(String password, String message) {
		assertThatThrownBy(() -> PasswordLinkService.checkPassword(password)).isInstanceOf(BusinessRuleException.class)
			.hasMessage(message)
			.extracting("field")
			.isEqualTo("password");
	}

}
