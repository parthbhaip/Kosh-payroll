package com.kosh.common;

/**
 * The record does not exist, or the current user may not know that it exists. Shown as the 404 page. The message
 * never reaches the page or {@link ErrorPageAdvice}'s log line, but dev's resolved-exception logging prints it, so it
 * must not contain PAN, bank or salary values.
 */
public class NotFoundException extends RuntimeException {

	public NotFoundException(String message) {
		super(message);
	}

}
