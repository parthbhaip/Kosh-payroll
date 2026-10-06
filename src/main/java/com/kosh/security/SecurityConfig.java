package com.kosh.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

import com.kosh.user.Role;

/**
 * Web security: public pages and static assets open to everyone, each {@link Area} limited to its roles, everything
 * else for signed-in users. Headers per CLAUDE.md > Security rules; Spring Security adds {@code nosniff},
 * {@code X-Frame-Options: DENY} and no-cache headers by default.
 * <p>
 * The login page is still Spring Security's generated one; T07 replaces it and adds landing by role.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

	/** No inline scripts or {@code eval}; inline styles stay allowed for SVG and the occasional style attribute. */
	public static final String CONTENT_SECURITY_POLICY = "default-src 'self'; script-src 'self'; img-src 'self' data:; "
			+ "style-src 'self' 'unsafe-inline'; frame-ancestors 'none'";

	@Bean
	SecurityFilterChain webSecurity(HttpSecurity http) throws Exception {
		http.authorizeHttpRequests(requests -> {
			requests.requestMatchers(HttpMethod.GET, "/", "/privacy", "/terms", "/help").permitAll();
			requests.requestMatchers("/error", "/css/**", "/fonts/**", "/img/**", "/js/**", "/webjars/**", "/favicon.ico")
				.permitAll();
			for (Area area : Area.values()) {
				requests.requestMatchers(area.prefix() + "/**")
					.hasAnyRole(area.roles().stream().map(Role::name).toArray(String[]::new));
			}
			requests.anyRequest().authenticated();
		})
			.formLogin(Customizer.withDefaults())
			.headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
				.referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.SAME_ORIGIN)));
		return http.build();
	}

	/**
	 * No accounts yet. Without a {@code UserDetailsService} bean Boot creates a "user" account with a generated
	 * password and logs it at WARN in every profile. T07 replaces this with the real one backed by the users table.
	 */
	@Bean
	UserDetailsService userDetailsService() {
		return new InMemoryUserDetailsManager();
	}

}
