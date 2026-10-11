package com.kosh.company;

/**
 * Kinds of holiday. National and festival holidays close the location (they are not working days); an optional
 * holiday is a working day that employees may take off as leave. Stored by name; never rename one.
 */
public enum HolidayType {

	NATIONAL("National", "Closed, like Republic Day or Independence Day."),
	FESTIVAL("Festival", "Closed for the festival. Its date usually moves every year."),
	OPTIONAL("Optional", "Open as usual. Employees may take the day off as leave.");

	private final String label;

	private final String description;

	HolidayType(String label, String description) {
		this.label = label;
		this.description = description;
	}

	public String label() {
		return label;
	}

	public String description() {
		return description;
	}

	/** The location is closed, so the day is not a working day. */
	public boolean closes() {
		return this != OPTIONAL;
	}

}
