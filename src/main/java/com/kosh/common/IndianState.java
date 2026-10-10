package com.kosh.common;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Indian states and union territories, in alphabetical order of name. Stored by the two-letter code of ISO 3166-2:IN
 * as revised on 2023-11-23 ({@code MH} for Maharashtra; {@code CG}, {@code TS} and {@code UK} replaced {@code CT},
 * {@code TG} and {@code UT}), which is also the key of the state-wise professional tax slabs.
 */
public enum IndianState {

	AN("Andaman and Nicobar Islands"), AP("Andhra Pradesh"), AR("Arunachal Pradesh"), AS("Assam"), BR("Bihar"),
	CH("Chandigarh"), CG("Chhattisgarh"), DH("Dadra and Nagar Haveli and Daman and Diu"), DL("Delhi"), GA("Goa"),
	GJ("Gujarat"), HR("Haryana"), HP("Himachal Pradesh"), JK("Jammu and Kashmir"), JH("Jharkhand"), KA("Karnataka"),
	KL("Kerala"), LA("Ladakh"), LD("Lakshadweep"), MP("Madhya Pradesh"), MH("Maharashtra"), MN("Manipur"),
	ML("Meghalaya"), MZ("Mizoram"), NL("Nagaland"), OD("Odisha"), PY("Puducherry"), PB("Punjab"), RJ("Rajasthan"),
	SK("Sikkim"), TN("Tamil Nadu"), TS("Telangana"), TR("Tripura"), UP("Uttar Pradesh"), UK("Uttarakhand"),
	WB("West Bengal");

	private final String label;

	IndianState(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

	/** Code to name, in display order, for a state picker. */
	public static Map<String, String> options() {
		Map<String, String> options = new LinkedHashMap<>();
		for (IndianState state : values()) {
			options.put(state.name(), state.label);
		}
		return options;
	}

}
