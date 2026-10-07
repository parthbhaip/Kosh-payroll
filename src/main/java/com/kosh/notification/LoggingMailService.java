package com.kosh.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The dev and test "mailbox": nothing leaves the machine. Recipient and subject are logged at INFO; the body, which can
 * hold a single-use link, only at DEBUG, which application-dev.properties switches on for this class alone.
 */
public class LoggingMailService implements MailService {

	private static final Logger log = LoggerFactory.getLogger(LoggingMailService.class);

	private final String from;

	public LoggingMailService(String from) {
		this.from = from;
	}

	@Override
	public void send(String to, String subject, String body) {
		log.info("E-mail not sent (app.mail.transport=log): from={} to={} subject=\"{}\"", from, to, subject);
		log.debug("E-mail body for {}:\n{}", to, body);
	}

}
