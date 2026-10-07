package com.kosh.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.web.servlet.FlashMap;
import org.springframework.web.servlet.support.SessionFlashMapManager;

import com.kosh.user.User;

/**
 * A failed sign-in goes back to the sign-in page with the reason and the e-mail as typed, carried once in a flash map
 * (never in the URL). Wrong password, unknown e-mail and invited-without-password all read the same, except the wrong
 * password that has just locked the account, which says so (as the change-password page does).
 */
final class LoginFailureHandler implements AuthenticationFailureHandler {

	static final String ERROR = "loginError";

	static final String EMAIL = "email";

	static final String BAD_CREDENTIALS = "The email or password is incorrect.";

	static final String DISABLED = "This account is disabled. Contact your Kosh administrator.";

	static final String LOCKED = ("This account is locked for %d minutes after %d wrong passwords in a row. "
			+ "Try again later, or reset your password.").formatted(User.LOCK_DURATION.toMinutes(), User.MAX_FAILED_LOGINS);

	static final String UNAVAILABLE = "We couldn’t sign you in just now. Try again in a minute.";

	private final SessionFlashMapManager flashMaps = new SessionFlashMapManager();

	@Override
	public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException exception) throws IOException {
		String loginPage = request.getContextPath() + "/login";
		FlashMap flash = new FlashMap();
		boolean lockedNow = request.getAttribute(SignInAttempts.LOCKED_NOW) != null;
		flash.put(ERROR, lockedNow ? LOCKED : message(exception));
		String email = (request.getParameter(EMAIL) != null) ? request.getParameter(EMAIL).strip() : "";
		if (!email.isEmpty()) {
			flash.put(EMAIL, (email.length() > 254) ? email.substring(0, 254) : email);
		}
		flash.setTargetRequestPath(loginPage);
		flashMaps.saveOutputFlashMap(flash, request, response);
		response.sendRedirect(loginPage);
	}

	static String message(AuthenticationException exception) {
		if (exception instanceof LockedException) {
			return LOCKED;
		}
		if (exception instanceof DisabledException) {
			return DISABLED;
		}
		return (exception instanceof BadCredentialsException) ? BAD_CREDENTIALS : UNAVAILABLE;
	}

}
