package com.kosh.security;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.kosh.web.Flash;

/**
 * The sign-in page (Spring Security processes its POST) and {@code /home}, which sends a signed-in user to the home
 * of their highest role. A signed-in user who opens the sign-in page goes home too, told that their form expired when
 * a stale CSRF token sent them here ({@code ?expired}).
 */
@Controller
class LoginController {

	static final String STALE_FORM = "That form had expired, so nothing was saved. Try again.";

	@GetMapping("/login")
	String login(Authentication authentication, Model model, RedirectAttributes redirect,
			@RequestParam(required = false) String logout, @RequestParam(required = false) String expired) {
		if (authentication != null) {
			if (expired != null) {
				// A stale form from a signed-in user (another tab, or a session renewed by remember-me).
				redirect.addFlashAttribute(Flash.ATTRIBUTE, Flash.warning(STALE_FORM));
			}
			return "redirect:" + Landing.pathFor(authentication);
		}
		Object error = model.getAttribute(LoginFailureHandler.ERROR);
		if (error != null) {
			model.addAttribute("notice", Flash.danger(error.toString()));
		}
		else if (expired != null) {
			model.addAttribute("notice", Flash.warning("Your session expired. Sign in again to continue."));
		}
		else if (logout != null) {
			model.addAttribute("notice", Flash.success("You’ve signed out."));
		}
		model.addAttribute("rememberMeDays", SecurityConfig.REMEMBER_ME_DAYS);
		model.addAttribute("signInPage", true);
		return "auth/login";
	}

	@GetMapping("/home")
	String home(Authentication authentication) {
		return "redirect:" + Landing.pathFor(authentication);
	}

}
