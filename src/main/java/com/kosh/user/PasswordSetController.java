package com.kosh.user;

import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.kosh.common.BusinessRuleException;
import com.kosh.user.UserToken.Purpose;
import com.kosh.web.Flash;

/**
 * {@code /password/set}: the page an invite e-mail links to (public; the token is the credential). A working link
 * shows whose account it is and asks for the password twice; a used, expired or unknown one says so.
 */
@Controller
@RequestMapping("/password/set")
class PasswordSetController {

	private final PasswordLinkService links;

	PasswordSetController(PasswordLinkService links) {
		this.links = links;
	}

	@GetMapping
	String form(@RequestParam(required = false) String token, Model model) {
		Optional<User> user = links.userFor(token, Purpose.INVITE);
		if (user.isEmpty()) {
			return "auth/link-invalid";
		}
		PasswordForm form = new PasswordForm();
		form.setToken(token);
		model.addAttribute("form", form);
		return formView(user.get(), model);
	}

	@PostMapping
	String set(@ModelAttribute("form") PasswordForm form, BindingResult errors, Model model,
			RedirectAttributes redirect) {
		Optional<User> user = links.userFor(form.getToken(), Purpose.INVITE);
		if (user.isEmpty()) {
			return "auth/link-invalid";
		}
		try {
			PasswordLinkService.checkPassword(form.getPassword());
		}
		catch (BusinessRuleException rule) {
			rule.addTo(errors);
		}
		if (!errors.hasFieldErrors("password") && !Objects.equals(form.getPassword(), form.getConfirmPassword())) {
			errors.rejectValue("confirmPassword", "mismatch", "The passwords don’t match.");
		}
		if (errors.hasErrors()) {
			return formView(user.get(), model);
		}
		User saved;
		try {
			saved = links.setPassword(form.getToken(), Purpose.INVITE, form.getPassword());
		}
		catch (BusinessRuleException spent) {
			// Used from another tab since the check above.
			return "auth/link-invalid";
		}
		redirect.addFlashAttribute("notice", Flash.success("Your password is set. Sign in to continue."));
		redirect.addFlashAttribute("email", saved.getEmail());
		return "redirect:/login";
	}

	private static String formView(User user, Model model) {
		model.addAttribute("name", user.getDisplayName());
		model.addAttribute("email", user.getEmail());
		return "auth/set-password";
	}

	/** The two password fields and the link token. Passwords are never echoed back into the page. */
	public static class PasswordForm {

		private String token;

		private String password;

		private String confirmPassword;

		public String getToken() {
			return token;
		}

		public void setToken(String token) {
			this.token = token;
		}

		public String getPassword() {
			return password;
		}

		public void setPassword(String password) {
			this.password = password;
		}

		public String getConfirmPassword() {
			return confirmPassword;
		}

		public void setConfirmPassword(String confirmPassword) {
			this.confirmPassword = confirmPassword;
		}

	}

}
