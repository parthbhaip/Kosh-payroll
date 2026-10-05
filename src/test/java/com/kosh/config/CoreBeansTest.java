package com.kosh.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.expression.ThymeleafEvaluationContext;
import org.thymeleaf.templateresolver.StringTemplateResolver;

import com.kosh.common.Fmt;
import com.kosh.common.HmacHasher;

/** Same context as {@code KoshApplicationTests}, so it is reused from the cache. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CoreBeansTest {

	private final ApplicationContext context;

	private final Clock clock;

	private final AppProperties app;

	@Autowired
	CoreBeansTest(ApplicationContext context, Clock clock, AppProperties app) {
		this.context = context;
		this.clock = clock;
		this.app = app;
	}

	@Test
	void testsSeeTheFixedClockInKolkata() {
		assertThat(clock).isSameAs(TestClockInitializer.CLOCK);
		assertThat(clock.getZone()).isEqualTo(ZoneId.of("Asia/Kolkata"));
		assertThat(LocalDateTime.now(clock)).isEqualTo(LocalDateTime.of(2026, 10, 5, 10, 0));
		assertThat(LocalDate.now(clock)).isEqualTo(LocalDate.of(2026, 10, 5));
	}

	@Test
	void applicationClockTicksInTheConfiguredZone() {
		Clock appClock = context.getBean("clock", Clock.class);
		assertThat(appClock.getZone()).isEqualTo(app.zone()).isEqualTo(ZoneId.of("Asia/Kolkata"));
		assertThat(appClock.instant()).isCloseTo(Instant.now(), within(1, ChronoUnit.MINUTES));
	}

	@Test
	void fmtIsTheBeanTemplatesReachAsAtFmt() {
		assertThat(context.getBean("fmt")).isInstanceOf(Fmt.class);

		SpringTemplateEngine engine = new SpringTemplateEngine();
		engine.setTemplateResolver(new StringTemplateResolver());
		Context model = new Context(Locale.ENGLISH);
		model.setVariable(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME,
				new ThymeleafEvaluationContext(context, null));
		model.setVariable("net", new BigDecimal("123456.5"));
		model.setVariable("paidOn", LocalDate.of(2026, 9, 30));
		model.setVariable("submittedAt", LocalDateTime.of(2026, 10, 5, 9, 55));

		String html = engine.process(
				"<p th:text=\"${@fmt.money(net)} + ' paid on ' + ${@fmt.date(paidOn)} + ', submitted ' + ${@fmt.relative(submittedAt)}\"></p>",
				model);

		assertThat(html).isEqualTo("<p>₹1,23,456.50 paid on 30 Sep 2026, submitted 5 minutes ago</p>");
	}

	@Test
	void lookupHashesUseTheConfiguredDataKey() {
		HmacHasher hasher = context.getBean(HmacHasher.class);
		assertThat(hasher.hash("ABCDE1234F")).isEqualTo(new HmacHasher(app).hash("ABCDE1234F")).hasSize(64);
	}

}
