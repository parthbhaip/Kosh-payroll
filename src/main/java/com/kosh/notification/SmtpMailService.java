package com.kosh.notification;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/** Sends through the SMTP server configured by {@code spring.mail.*} (mysql profile). */
public class SmtpMailService implements MailService {

	private final JavaMailSender sender;

	private final String from;

	public SmtpMailService(JavaMailSender sender, String from) {
		this.sender = sender;
		this.from = from;
	}

	@Override
	public void send(String to, String subject, String body) {
		SimpleMailMessage message = new SimpleMailMessage();
		message.setFrom(from);
		message.setTo(to);
		message.setSubject(subject);
		message.setText(body);
		sender.send(message);
	}

}
