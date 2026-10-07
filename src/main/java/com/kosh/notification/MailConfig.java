package com.kosh.notification;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

import com.kosh.config.AppProperties;

/** One {@link MailService}, chosen by {@code app.mail.transport} (never by profile name). */
@Configuration(proxyBeanMethods = false)
class MailConfig {

	@Bean
	MailService mailService(AppProperties app, ObjectProvider<JavaMailSender> smtp) {
		String from = app.mail().from();
		return switch (app.mail().transport()) {
			case LOG -> new LoggingMailService(from);
			// Boot creates the JavaMailSender when spring.mail.host is set; the mysql profile always sets it.
			case SMTP -> new SmtpMailService(smtp.getObject(), from);
		};
	}

}
