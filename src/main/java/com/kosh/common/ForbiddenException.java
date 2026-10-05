package com.kosh.common;

/**
 * The current user is signed in but may not do this. Shown as the 403 page; {@link ErrorPageAdvice} logs the user and
 * path at WARN. The message never reaches the page or that log line, but dev's resolved-exception logging prints it,
 * so it must not contain PAN, bank or salary values.
 */
public class ForbiddenException extends RuntimeException {

	public ForbiddenException(String message) {
		super(message);
	}

}
