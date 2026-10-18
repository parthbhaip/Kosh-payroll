package com.kosh.employee;

import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.kosh.common.IndianState;
import com.kosh.common.Text;
import com.kosh.company.CompanyProfile;

/**
 * A postal address in India, tidied on construction (trimmed, blank as null, PIN code without spaces). An employee
 * either has a complete address (line 1, city, state and PIN code) or none: a missing address is a null
 * {@code Address}, never one with empty parts. The employee maps the columns ({@code current_*}, {@code permanent_*}).
 */
@Embeddable
public record Address(
		@NotBlank(message = "Enter the address.")
		@Size(max = CompanyProfile.MAX_ADDRESS_LINE, message = "Use at most " + CompanyProfile.MAX_ADDRESS_LINE + " characters.")
		String line1,

		@Size(max = CompanyProfile.MAX_ADDRESS_LINE, message = "Use at most " + CompanyProfile.MAX_ADDRESS_LINE + " characters.")
		String line2,

		@NotBlank(message = "Enter the city.")
		@Size(max = CompanyProfile.MAX_CITY, message = "Use at most " + CompanyProfile.MAX_CITY + " characters.")
		String city,

		@NotNull(message = "Choose the state.")
		@Enumerated(EnumType.STRING)
		@JdbcTypeCode(SqlTypes.VARCHAR)
		IndianState state,

		@NotBlank(message = "Enter the PIN code.")
		@Pattern(regexp = CompanyProfile.PINCODE_FORMAT, message = "Enter a 6-digit PIN code.")
		String pincode) {

	public Address {
		line1 = Text.clean(line1);
		line2 = Text.clean(line2);
		city = Text.clean(city);
		pincode = Text.code(pincode);
	}

}
