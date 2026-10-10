package com.kosh.org;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.kosh.common.IndianState;
import com.kosh.common.Text;
import com.kosh.company.CompanyProfile;

/**
 * What HR enters for one item of the organisation structure, tidied on construction (the code upper-cased without
 * spaces, the rest trimmed, blank as null). {@code headUserId} is for departments only; the address and
 * {@code ptApplicable} for work locations only, whose required fields are in the {@link Location} group. These
 * annotations are the one statement of the rules: {@link OrgService} checks them for every caller, and the form shows
 * every broken one at once.
 */
public record OrgDetails(
		@NotBlank(message = "Enter a code.")
		@Pattern(regexp = OrgItem.CODE_FORMAT, message = "Use 2 to 10 letters, digits or hyphens, starting with a letter or digit.")
		String code,

		@NotBlank(message = "Enter a name.")
		@Size(max = OrgItem.MAX_NAME, message = "Use at most " + OrgItem.MAX_NAME + " characters.")
		String name,

		Long headUserId,

		@NotBlank(groups = Location.class, message = "Enter the address.")
		@Size(max = CompanyProfile.MAX_ADDRESS_LINE, message = "Use at most " + CompanyProfile.MAX_ADDRESS_LINE + " characters.")
		String addressLine1,

		@Size(max = CompanyProfile.MAX_ADDRESS_LINE, message = "Use at most " + CompanyProfile.MAX_ADDRESS_LINE + " characters.")
		String addressLine2,

		@NotBlank(groups = Location.class, message = "Enter the city.")
		@Size(max = CompanyProfile.MAX_CITY, message = "Use at most " + CompanyProfile.MAX_CITY + " characters.")
		String city,

		@NotNull(groups = Location.class, message = "Choose the state.")
		IndianState state,

		@NotBlank(groups = Location.class, message = "Enter the PIN code.")
		@Pattern(regexp = CompanyProfile.PINCODE_FORMAT, message = "Enter a 6-digit PIN code.")
		String pincode,

		boolean ptApplicable) {

	/** Validation group: the fields a work location must have. */
	public interface Location {

	}

	public OrgDetails {
		code = Text.code(code);
		name = Text.clean(name);
		addressLine1 = Text.clean(addressLine1);
		addressLine2 = Text.clean(addressLine2);
		city = Text.clean(city);
		pincode = Text.code(pincode);
	}

	/** A department, designation, grade or cost centre: just a code and a name. */
	public static OrgDetails of(String code, String name) {
		return new OrgDetails(code, name, null, null, null, null, null, null, false);
	}

}
