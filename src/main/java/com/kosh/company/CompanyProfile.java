package com.kosh.company;

import java.util.ArrayList;
import java.util.List;

import com.kosh.common.IndianState;

/**
 * Who the employer is, as printed on payslips, salary letters and statutory files. Empty values are null. Formats,
 * checked by the settings form and again by {@link CompanyService}: PAN {@code ABCDE1234F}, TAN {@code ABCD12345E}, PF
 * establishment code {@code MHKOP0012345000}, ESI employer code of 17 digits, Maharashtra PT registration (PTRC)
 * {@code 27531234567P}.
 */
public record CompanyProfile(String legalName, String brandName, String addressLine1, String addressLine2, String city,
		IndianState state, String pincode, String pan, String tan, String pfEstablishmentCode, String esiEmployerCode,
		String ptRegistrationNumber, String signatoryName, String signatoryDesignation) {

	public static final String PINCODE_FORMAT = "[1-9][0-9]{5}";

	public static final String PAN_FORMAT = "[A-Z]{5}[0-9]{4}[A-Z]";

	static final String TAN_FORMAT = "[A-Z]{4}[0-9]{5}[A-Z]";

	static final String PF_CODE_FORMAT = "[A-Z]{5}[0-9]{10}";

	static final String ESI_CODE_FORMAT = "[0-9]{17}";

	static final String PTRC_FORMAT = "[0-9]{11}P";

	// Text lengths: the V4 column sizes. The codes above are bounded by their formats.

	static final int MAX_LEGAL_NAME = 150;

	static final int MAX_BRAND_NAME = 80;

	public static final int MAX_ADDRESS_LINE = 150;

	public static final int MAX_CITY = 60;

	static final int MAX_SIGNATORY_NAME = 120;

	static final int MAX_DESIGNATION = 80;

	/** Something payroll outputs will need that is not filled in yet, and why. */
	public record Gap(String item, String reason) {

	}

	/** The short name for screens: the brand name, else the legal name, else null before set-up. */
	public String displayName() {
		return (brandName != null) ? brandName : legalName;
	}

	/** What is still missing for payslips and statutory files, in the order to fill it in. Empty when complete. */
	public List<Gap> gaps() {
		List<Gap> gaps = new ArrayList<>();
		if (legalName == null || addressLine1 == null) {
			gaps.add(new Gap("Legal name and address", "Printed at the top of every payslip."));
		}
		if (pan == null || tan == null) {
			gaps.add(new Gap("PAN and TAN", "Needed for TDS statements."));
		}
		if (pfEstablishmentCode == null) {
			gaps.add(new Gap("PF establishment code", "Needed for the monthly PF ECR file."));
		}
		if (esiEmployerCode == null) {
			gaps.add(new Gap("ESI employer code", "Needed for the monthly ESI contribution file."));
		}
		if (ptRegistrationNumber == null) {
			gaps.add(new Gap("PT registration number", "Needed for the professional tax summary."));
		}
		if (signatoryName == null) {
			gaps.add(new Gap("Authorised signatory", "Signs salary letters and annexures."));
		}
		return gaps;
	}

}
