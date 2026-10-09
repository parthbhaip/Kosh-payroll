package com.kosh.security;

import java.time.Duration;
import java.util.LinkedHashMap;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.security.autoconfigure.web.servlet.PathRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AccountStatusUserDetailsChecker;
import org.springframework.security.authentication.AuthenticationEventPublisher;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.RememberMeAuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.access.DelegatingAccessDeniedHandler;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.session.SessionLimit;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.session.HttpSessionEventPublisher;

import com.kosh.common.HmacHasher;
import com.kosh.user.Role;

/**
 * Web security: public pages, sign-in, password links and static assets open to everyone, each {@link Area} limited
 * to its roles, everything else for signed-in users. Form sign-in by e-mail lands on the home of the user's highest
 * role ({@link Landing}); sign-out is a POST. Headers per CLAUDE.md > Security rules; Spring Security adds
 * {@code nosniff}, {@code X-Frame-Options: DENY} and no-cache headers by default. Services check roles a second time
 * with {@code @PreAuthorize}. Every signed-in session is registered, so disabling a user or changing their roles can
 * end their sessions ({@link #ENDED_URL}).
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfig {

	/** No inline scripts or {@code eval}; inline styles stay allowed for SVG and the occasional style attribute. */
	public static final String CONTENT_SECURITY_POLICY = "default-src 'self'; script-src 'self'; img-src 'self' data:; "
			+ "style-src 'self' 'unsafe-inline'; frame-ancestors 'none'";

	/** The H2 console (dev only) runs inline scripts and frames itself; nothing else gets this policy. */
	static final String H2_CONSOLE_POLICY = "default-src 'self'; script-src 'self' 'unsafe-inline'; "
			+ "img-src 'self' data:; style-src 'self' 'unsafe-inline'; frame-ancestors 'self'";

	/** How long "Keep me signed in" lasts. */
	public static final int REMEMBER_ME_DAYS = 14;

	/** Where a session ended by an admin (account disabled, roles changed) goes on its next request. */
	static final String ENDED_URL = "/login?ended";

	@Bean
	SecurityFilterChain webSecurity(HttpSecurity http, AuthenticationManager authenticationManager,
			KoshUserDetailsService users, HmacHasher hasher, SessionRegistry sessionRegistry) throws Exception {
		http.authorizeHttpRequests(requests -> {
			requests.requestMatchers(HttpMethod.GET, "/", "/privacy", "/terms", "/help", "/login").permitAll();
			// Set-password (invite) and forgot/reset-password pages reached from e-mailed links. Pages for
			// signed-in users, such as changing a password, must not live under /password.
			requests.requestMatchers("/password/**").permitAll();
			requests.requestMatchers("/error", "/css/**", "/fonts/**", "/img/**", "/js/**", "/webjars/**", "/favicon.ico")
				.permitAll();
			for (Area area : Area.values()) {
				requests.requestMatchers(area.prefix() + "/**")
					.hasAnyRole(area.roles().stream().map(Role::name).toArray(String[]::new));
			}
			requests.anyRequest().authenticated();
		})
			.authenticationManager(authenticationManager)
			.formLogin(form -> form.loginPage("/login")
				.usernameParameter("email")
				.passwordParameter("password")
				.successHandler(new LandingSuccessHandler())
				.failureHandler(new LoginFailureHandler()))
			.rememberMe(remember -> remember.key(rememberMeKey(hasher))
				.userDetailsService(users)
				.tokenValiditySeconds((int) Duration.ofDays(REMEMBER_ME_DAYS).toSeconds()))
			.logout(logout -> logout.logoutSuccessUrl("/login?logout").deleteCookies("JSESSIONID"))
			.sessionManagement(sessions -> sessions.sessionConcurrency(concurrency -> concurrency
				.maximumSessions(SessionLimit.UNLIMITED)
				.sessionRegistry(sessionRegistry)
				.expiredUrl(ENDED_URL)))
			.exceptionHandling(exceptions -> exceptions.accessDeniedHandler(accessDeniedHandler()))
			.headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
				.referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.SAME_ORIGIN)));
		return http.build();
	}

	/**
	 * Dev only, while the H2 console is switched on: ADMIN users only, framed by itself, its own inline scripts, and no
	 * Spring CSRF token (its forms carry none; the console's own session id guards them).
	 */
	@Bean
	@Order(1)
	@ConditionalOnBooleanProperty("spring.h2.console.enabled")
	SecurityFilterChain h2ConsoleSecurity(HttpSecurity http) throws Exception {
		http.securityMatcher(PathRequest.toH2Console())
			.authorizeHttpRequests(requests -> requests.anyRequest().hasRole(Role.ADMIN.name()))
			.csrf(csrf -> csrf.disable())
			.exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(new LoginUrlAuthenticationEntryPoint("/login")))
			.headers(headers -> headers.frameOptions(frames -> frames.sameOrigin())
				.contentSecurityPolicy(csp -> csp.policyDirectives(H2_CONSOLE_POLICY))
				.referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.SAME_ORIGIN)));
		return http.build();
	}

	/**
	 * Passwords, then remember-me cookies. Disabled is checked only after a correct password, so a wrong guess on a
	 * disabled account reads like an unknown e-mail. A lock is checked before the password, so a locked account stays
	 * shut whatever is typed and the lock never tells a guesser when a password is right. Trade-off (accepted, see
	 * NOTES T07): a locked account answers "locked" to any password, quickly and without the bcrypt work, while an
	 * unknown e-mail never locks, so anyone willing to lock an account can learn that it exists. Sign-in events go to
	 * the application: {@link SignInAttempts} counts wrong passwords and locks, and {@link SignInAudit} records each attempt.
	 */
	@Bean
	AuthenticationManager authenticationManager(KoshUserDetailsService users, PasswordEncoder passwordEncoder,
			HmacHasher hasher, AuthenticationEventPublisher events) {
		DaoAuthenticationProvider passwords = new DaoAuthenticationProvider(users);
		passwords.setPasswordEncoder(passwordEncoder);
		passwords.setPreAuthenticationChecks(user -> {
			if (!user.isAccountNonLocked()) {
				throw new LockedException("Account is locked");
			}
		});
		passwords.setPostAuthenticationChecks(new AccountStatusUserDetailsChecker());
		ProviderManager manager = new ProviderManager(passwords, new RememberMeAuthenticationProvider(rememberMeKey(hasher)));
		manager.setAuthenticationEventPublisher(events);
		return manager;
	}

	/**
	 * Signed-in sessions per user, in memory (one application instance), from form and remember-me sign-ins. The
	 * session event publisher removes sessions that end or time out.
	 */
	@Bean
	SessionRegistry sessionRegistry() {
		return new SessionRegistryImpl();
	}

	@Bean
	HttpSessionEventPublisher httpSessionEventPublisher() {
		return new HttpSessionEventPublisher();
	}

	/** BCrypt, stored with its {@code {bcrypt}} prefix so the algorithm can change later without a reset. */
	@Bean
	PasswordEncoder passwordEncoder() {
		return PasswordEncoderFactories.createDelegatingPasswordEncoder();
	}

	/**
	 * Signs remember-me cookies. Derived from the data key under its own label (not a look-up hash), so it is secret,
	 * stable across restarts, and not new config.
	 */
	private static String rememberMeKey(HmacHasher hasher) {
		return hasher.deriveSecret("kosh/remember-me-key/v1");
	}

	/**
	 * An expired or missing CSRF token means a stale form: {@code /login?expired} asks an anonymous user to sign in
	 * again and sends a signed-in one home with a notice ({@link LoginController}), instead of a bare 403.
	 */
	private static AccessDeniedHandler accessDeniedHandler() {
		LinkedHashMap<Class<? extends AccessDeniedException>, AccessDeniedHandler> handlers = new LinkedHashMap<>();
		handlers.put(CsrfException.class, (request, response, denied) -> response
			.sendRedirect(request.getContextPath() + "/login?expired"));
		return new DelegatingAccessDeniedHandler(handlers, new AccessDeniedHandlerImpl());
	}

	/** A saved request (a link opened before signing in) wins; otherwise the home of the highest role. */
	private static final class LandingSuccessHandler extends SavedRequestAwareAuthenticationSuccessHandler {

		@Override
		protected String determineTargetUrl(HttpServletRequest request, HttpServletResponse response,
				Authentication authentication) {
			return Landing.pathFor(authentication);
		}

	}

}
