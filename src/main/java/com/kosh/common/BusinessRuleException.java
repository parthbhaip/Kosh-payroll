package com.kosh.common;

import org.springframework.validation.Errors;

/**
 * A service refused a change that breaks a business rule ("Kosh needs at least one active admin"). The message is
 * written for the person on the page and never holds PAN, bank or salary values. {@code field} names the form field it
 * belongs to, or is null for the form as a whole.
 */
public class BusinessRuleException extends RuntimeException {

	private final String field;

	public BusinessRuleException(String field, String message) {
		super(message);
		this.field = field;
	}

	public String getField() {
		return field;
	}

	/** Shows the message on the form: next to its field, or as a form-wide error. */
	public void addTo(Errors errors) {
		if (field != null) {
			errors.rejectValue(field, "rule", getMessage());
		}
		else {
			errors.reject("rule", getMessage());
		}
	}

}
