package com.kosh.statutory;

/** The two income-tax regimes. The new regime is the default; an employee may choose the old one. */
public enum TaxRegime {

	NEW("New regime"), OLD("Old regime");

	private final String label;

	TaxRegime(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

}
