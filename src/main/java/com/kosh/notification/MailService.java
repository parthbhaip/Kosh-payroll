package com.kosh.notification;

/**
 * Sends plain-text e-mails. {@link MailConfig} picks the implementation from {@code app.mail.transport}: LOG
 * ({@link LoggingMailService}, dev and test) or SMTP ({@link SmtpMailService}, mysql).
 */
public interface MailService {

	/**
	 * Sends one e-mail from the configured sender address.
	 * @throws org.springframework.mail.MailException when the mail server refuses or cannot be reached
	 */
	void send(String to, String subject, String body);

}
