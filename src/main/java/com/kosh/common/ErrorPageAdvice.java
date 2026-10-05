package com.kosh.common;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Maps the application's own exceptions to the branded error pages. Everything else (unknown URLs, Spring Security's
 * 403, uncaught exceptions) reaches the same templates through Boot's {@code /error} controller, which picks
 * {@code templates/error/<status>.html} and falls back to {@code templates/error.html}.
 * <p>
 * Denials are logged with the user, method and path only: never the exception message or the query string, which can
 * carry PAN, bank or salary values. A run of 403s from one user is how ID probing shows up.
 */
@ControllerAdvice
public class ErrorPageAdvice {

	private static final Logger log = LoggerFactory.getLogger(ErrorPageAdvice.class);

	@ExceptionHandler(NotFoundException.class)
	@ResponseStatus(HttpStatus.NOT_FOUND)
	public String notFound(HttpServletRequest request) {
		log.debug("Not found: user={} {} {}", request.getRemoteUser(), request.getMethod(), request.getRequestURI());
		return "error/404";
	}

	@ExceptionHandler(ForbiddenException.class)
	@ResponseStatus(HttpStatus.FORBIDDEN)
	public String forbidden(HttpServletRequest request) {
		log.warn("Access denied: user={} {} {}", request.getRemoteUser(), request.getMethod(), request.getRequestURI());
		return "error/403";
	}

}
