package com.kosh.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.nio.file.Path;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.kosh.config.AppProperties;
import com.kosh.config.AppProperties.Transport;

/** The two mail transports and how {@code app.mail.transport} picks one. */
class MailServiceTest {

	private static final String BODY = "Choose your password here:\nhttp://localhost:8080/password/set?token=secret-token";

	@Test
	void logTransportLogsRecipientAndSubjectAndTheBodyOnlyAtDebug() {
		Logger logger = (Logger) LoggerFactory.getLogger(LoggingMailService.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		Level level = logger.getLevel();
		logger.addAppender(appender);
		logger.setLevel(Level.DEBUG);
		try {
			new LoggingMailService("no-reply@kosh.test").send("meera@panchganga.test", "You're invited to Kosh", BODY);
		}
		finally {
			logger.detachAppender(appender);
			logger.setLevel(level);
		}

		assertThat(appender.list).extracting(ILoggingEvent::getLevel).containsExactly(Level.INFO, Level.DEBUG);
		assertThat(appender.list.get(0).getFormattedMessage())
			.isEqualTo("E-mail not sent (app.mail.transport=log): from=no-reply@kosh.test to=meera@panchganga.test "
					+ "subject=\"You're invited to Kosh\"")
			.doesNotContain("secret-token");
		assertThat(appender.list.get(1).getFormattedMessage()).contains("token=secret-token");
	}

	@Test
	void smtpTransportSendsPlainTextFromTheConfiguredSender() {
		JavaMailSender sender = mock(JavaMailSender.class);

		new SmtpMailService(sender, "payroll@panchganga.test").send("meera@panchganga.test", "You're invited to Kosh",
				BODY);

		ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
		verify(sender).send(sent.capture());
		assertThat(sent.getValue().getFrom()).isEqualTo("payroll@panchganga.test");
		assertThat(sent.getValue().getTo()).containsExactly("meera@panchganga.test");
		assertThat(sent.getValue().getSubject()).isEqualTo("You're invited to Kosh");
		assertThat(sent.getValue().getText()).isEqualTo(BODY);
	}

	@Test
	void theTransportSettingPicksTheImplementation() {
		ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(MailConfig.class);

		runner.withBean(AppProperties.class, () -> app(Transport.LOG))
			.run(context -> assertThat(context).getBean(MailService.class).isInstanceOf(LoggingMailService.class));
		runner.withBean(AppProperties.class, () -> app(Transport.SMTP))
			.withBean(JavaMailSender.class, () -> mock(JavaMailSender.class))
			.run(context -> assertThat(context).getBean(MailService.class).isInstanceOf(SmtpMailService.class));
		// SMTP without a configured mail server is a startup error, not a silent log.
		runner.withBean(AppProperties.class, () -> app(Transport.SMTP)).run(context -> assertThat(context).hasFailed());
	}

	private static AppProperties app(Transport transport) {
		return new AppProperties("Kosh", ZoneId.of("Asia/Kolkata"), Path.of("uploads"), false, "unused",
				"http://localhost:8080", new AppProperties.Mail(transport, "no-reply@kosh.test"));
	}

}
