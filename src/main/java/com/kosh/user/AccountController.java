package com.kosh.user;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.kosh.common.BusinessRuleException;
import com.kosh.web.Flash;

/**
 * {@code /account/password}: any signed-in user changes their own password (the current one is required). Not under
 * {@code /password}, which is public. Linked from the user menu.
 */
@Controller
@RequestMapping("/account/password")
class AccountController {

	private static final String VIEW = "account/password";

	private final AccountService service;

	AccountController(AccountService service) {
		this.service = service;
	}

	@ModelAttribute
	void lockRule(Model model) {
		model.addAttribute("maxFailures", User.MAX_FAILED_LOGINS);
		model.addAttribute("lockMinutes", User.LOCK_DURATION.toMinutes());
	}

	@GetMapping
	String form(Model model) {
		model.addAttribute("form", new ChangePasswordForm());
		return VIEW;
	}

	@PostMapping
	String change(@ModelAttribute("form") ChangePasswordForm form, BindingResult errors, Authentication self,
			HttpServletRequest request, RedirectAttributes redirect) {
		if (form.getCurrentPassword() == null || form.getCurrentPassword().isEmpty()) {
			errors.rejectValue("currentPassword", "required", "Enter your current password.");
		}
		PasswordSetController.checkNewPassword(form.getPassword(), form.getConfirmPassword(), errors);
		if (!errors.hasErrors()) {
			try {
				service.changePassword(self, form.getCurrentPassword(), form.getPassword(), request.getSession().getId());
				redirect.addFlashAttribute(Flash.ATTRIBUTE, Flash.success("Your password is changed")
					.withText("Use it the next time you sign in. Kosh signed you out on your other devices."));
				return "redirect:/account/password";
			}
			catch (BusinessRuleException rule) {
				rule.addTo(errors);
			}
		}
		return VIEW;
	}

	/** Current, new and repeated password. None of them is ever written back into the page. */
	public static class ChangePasswordForm {

		private String currentPassword;

		private String password;

		private String confirmPassword;

		public String getCurrentPassword() {
			return currentPassword;
		}

		public void setCurrentPassword(String currentPassword) {
			this.currentPassword = currentPassword;
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
