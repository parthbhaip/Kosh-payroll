package com.kosh.employee;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embeddable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.kosh.common.EncryptedStringConverter;
import com.kosh.common.Masking;
import com.kosh.common.Text;

/**
 * Where salary is paid: all four parts or nothing (no bank details is a null {@code BankAccount}, which the payroll
 * pre-checks flag). Tidied on construction: IFSC and account number upper case without spaces. The account number is
 * encrypted at rest and shown masked everywhere except the bank file; {@link #toString()} masks it too, so a stray log
 * line never carries it.
 */
@Embeddable
public record BankAccount(
		@NotBlank(message = "Enter the bank name.")
		@Size(max = 100, message = "Use at most 100 characters.")
		@Column(name = "bank_name", length = 100)
		String bankName,

		@NotBlank(message = "Enter the IFSC.")
		@Pattern(regexp = BankAccount.IFSC_FORMAT, message = "Enter an IFSC like SBIN0001234: four letters, a zero, then six letters or digits.")
		@Column(name = "bank_ifsc", length = 11)
		String ifsc,

		@NotBlank(message = "Enter the account number.")
		@Pattern(regexp = BankAccount.ACCOUNT_FORMAT, message = "Enter an account number of 9 to 18 digits.")
		@Convert(converter = EncryptedStringConverter.class)
		@Column(name = "bank_account_number", length = 255)
		String accountNumber,

		@NotBlank(message = "Enter the account holder’s name.")
		@Size(max = 120, message = "Use at most 120 characters.")
		@Column(name = "bank_account_holder", length = 120)
		String holderName) {

	/** Indian Financial System Code: bank (4 letters), a zero, branch (6 letters or digits). */
	public static final String IFSC_FORMAT = "[A-Z]{4}0[A-Z0-9]{6}";

	/** Savings and salary account numbers of Indian banks: 9 to 18 digits. */
	public static final String ACCOUNT_FORMAT = "[0-9]{9,18}";

	public BankAccount {
		bankName = Text.clean(bankName);
		ifsc = Text.code(ifsc);
		accountNumber = Text.code(accountNumber);
		holderName = Text.clean(holderName);
	}

	@Override
	public String toString() {
		return "BankAccount[bankName=%s, ifsc=%s, accountNumber=%s, holderName=%s]".formatted(bankName, ifsc,
				Masking.account(accountNumber), holderName);
	}

}
