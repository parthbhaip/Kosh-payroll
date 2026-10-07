package com.kosh.user;

import java.util.Objects;
import java.util.Optional;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

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
 * The public password pages under {@code /password} (the e-mailed token is the credential): {@code /set} from an
 * invite, {@code /reset} from a reset e-mail, and {@code /forgot}, which asks for that e-mail. A working link shows
 * whose account it is and asks for the password twice; a used, expired or unknown one says so. {@code /forgot} answers
 * the same whether or not the address has an account.
 */
@Controller
@RequestMapping("/password")
class PasswordSetController {

	private final PasswordLinkService links;

	private final PasswordResetService resets;

	PasswordSetController(PasswordLinkService links, PasswordResetService resets) {
		this.links = links;
		this.resets = resets;
	}

	@GetMapping("/set")
	String inviteForm(@RequestParam(required = false) String token, Model model) {
		return form(token, Purpose.INVITE, model);
	}

	@PostMapping("/set")
	String setFromInvite(@ModelAttribute("form") PasswordForm form, BindingResult errors, Model model,
			RedirectAttributes redirect) {
		return set(form, errors, Purpose.INVITE, model, redirect);
	}

	@GetMapping("/reset")
	String resetForm(@RequestParam(required = false) String token, Model model) {
		return form(token, Purpose.RESET, model);
	}

	@PostMapping("/reset")
	String setFromReset(@ModelAttribute("form") PasswordForm form, BindingResult errors, Model model,
			RedirectAttributes redirect) {
		return set(form, errors, Purpose.RESET, model, redirect);
	}

	/** The request form, or after a request ({@code ?sent}) the "check your email" page. */
	@GetMapping("/forgot")
	String forgotForm(@RequestParam(required = false) String sent, Model model) {
		if (sent != null) {
			model.addAttribute("validMinutes", Purpose.RESET.validity().toMinutes());
			return "auth/reset-sent";
		}
		model.addAttribute("form", new ForgotForm());
		return "auth/forgot-password";
	}

	@PostMapping("/forgot")
	String forgot(@Valid @ModelAttribute("form") ForgotForm form, BindingResult errors, RedirectAttributes redirect) {
		if (errors.hasErrors()) {
			return "auth/forgot-password";
		}
		resets.requestReset(form.getEmail());
		// The address as typed, once, in the session: never in the URL.
		redirect.addFlashAttribute("email", User.normalizeEmail(form.getEmail()));
		return "redirect:/password/forgot?sent";
	}

	private String form(String token, Purpose purpose, Model model) {
		Optional<User> user = links.userFor(token, purpose);
		if (user.isEmpty()) {
			return linkInvalid(purpose, model);
		}
		PasswordForm form = new PasswordForm();
		form.setToken(token);
		model.addAttribute("form", form);
		return formView(user.get(), purpose, model);
	}

	private String set(PasswordForm form, BindingResult errors, Purpose purpose, Model model,
			RedirectAttributes redirect) {
		Optional<User> user = links.userFor(form.getToken(), purpose);
		if (user.isEmpty()) {
			return linkInvalid(purpose, model);
		}
		checkNewPassword(form.getPassword(), form.getConfirmPassword(), errors);
		if (errors.hasErrors()) {
			return formView(user.get(), purpose, model);
		}
		User saved;
		try {
			saved = links.setPassword(form.getToken(), purpose, form.getPassword());
		}
		catch (BusinessRuleException spent) {
			// Used from another tab since the check above.
			return linkInvalid(purpose, model);
		}
		redirect.addFlashAttribute("notice", Flash.success((purpose == Purpose.RESET)
				? "Your password is changed. Sign in with your new password." : "Your password is set. Sign in to continue."));
		redirect.addFlashAttribute("email", saved.getEmail());
		return "redirect:/login";
	}

	/** The password rules and the second copy, on fields {@code password} and {@code confirmPassword}. */
	static void checkNewPassword(String password, String confirmPassword, BindingResult errors) {
		try {
			PasswordLinkService.checkPassword(password);
		}
		catch (BusinessRuleException rule) {
			rule.addTo(errors);
		}
		if (!errors.hasFieldErrors("password") && !Objects.equals(password, confirmPassword)) {
			errors.rejectValue("confirmPassword", "mismatch", "The passwords don’t match.");
		}
	}

	private static String formView(User user, Purpose purpose, Model model) {
		model.addAttribute("name", user.getDisplayName());
		model.addAttribute("email", user.getEmail());
		model.addAttribute("reset", purpose == Purpose.RESET);
		return "auth/set-password";
	}

	private static String linkInvalid(Purpose purpose, Model model) {
		model.addAttribute("reset", purpose == Purpose.RESET);
		return "auth/link-invalid";
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

	/** The work e-mail a reset link should go to; trimmed. */
	public static class ForgotForm {

		@NotBlank(message = "Enter your work email.")
		@Email(message = "Enter an email like name@company.in.")
		@Size(max = 254, message = "Use at most 254 characters.")
		private String email;

		public String getEmail() {
			return email;
		}

		public void setEmail(String email) {
			this.email = (email != null) ? email.strip() : null;
		}

	}

}
