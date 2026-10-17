package com.kosh.statutory;

import java.time.LocalDate;

import com.kosh.common.IndianState;

/**
 * Which version of a rule family something used, and where its values come from. {@code state} is null for rules
 * that apply across India; {@code effectiveTo} is null while no later version is added.
 */
public record VersionInfo(long id, RuleFamily family, IndianState state, LocalDate effectiveFrom,
		LocalDate effectiveTo, String sourceNote, LocalDate verifiedOn) {

	static VersionInfo of(RuleVersion version) {
		IndianState state = RuleVersion.NATIONAL.equals(version.getScope()) ? null
				: IndianState.valueOf(version.getScope());
		return new VersionInfo(version.getId(), version.getFamily(), state, version.getEffectiveFrom(),
				version.getEffectiveTo(), version.getSourceNote(), version.getVerifiedOn());
	}

	/** "Provident Fund", "Professional tax, Maharashtra". */
	public String title() {
		return family.title(state);
	}

	/** The family's card id on the rules page: {@code pf}, {@code pt-mh}. */
	public String anchor() {
		return family.anchor(state);
	}

}
