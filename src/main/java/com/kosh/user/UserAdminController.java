package com.kosh.user;

import java.util.EnumSet;
import java.util.Set;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.mail.MailException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.kosh.common.BusinessRuleException;
import com.kosh.user.UserAdminService.Detail;
import com.kosh.user.UserAdminService.Saved;
import com.kosh.web.Flash;

/** {@code /admin/users}: the user list, invites, and each user's name, roles and access. ADMIN only (URL rule and service). */
@Controller
@RequestMapping("/admin/users")
class UserAdminController {

	static final int PAGE_SIZE = 25;

	/** Sortable columns; anything else in {@code ?sort=} is ignored, so nobody sorts by a password hash. */
	private static final Set<String> SORTABLE = Set.of("displayName", "email", "lastLoginAt");

	private static final String LIST = "/admin/users";

	private final UserAdminService service;

	UserAdminController(UserAdminService service) {
		this.service = service;
	}

	@GetMapping
	String list(@RequestParam(required = false) String q, @RequestParam(required = false) String role,
			@PageableDefault(sort = "displayName") Pageable pageable, Model model) {
		Role roleFilter = parseRole(role);
		Sort sort = Sort.by(pageable.getSort().filter(order -> SORTABLE.contains(order.getProperty())).toList());
		if (sort.isUnsorted()) {
			sort = Sort.by("displayName");
		}
		// The id keeps paging stable when names or sign-in times repeat.
		Pageable page = PageRequest.of(pageable.getPageNumber(), PAGE_SIZE, sort.and(Sort.by("id")));
		String text = (q != null) ? q.strip() : "";
		model.addAttribute("page", service.list(text, roleFilter, page));
		model.addAttribute("sort", sort);
		model.addAttribute("q", text);
		model.addAttribute("role", roleFilter);
		model.addAttribute("allRoles", Role.values());
		model.addAttribute("filtered", !text.isEmpty() || roleFilter != null);
		return "admin/users/list";
	}

	@GetMapping("/invite")
	String inviteForm(Model model) {
		InviteForm form = new InviteForm();
		form.setRoles(EnumSet.of(Role.EMPLOYEE));
		model.addAttribute("form", form);
		return inviteView(model);
	}

	@PostMapping("/invite")
	String invite(@Valid @ModelAttribute("form") InviteForm form, BindingResult errors, Authentication actor, Model model,
			RedirectAttributes redirect) {
		checkRoles(form, errors);
		if (!errors.hasErrors()) {
			try {
				long id = service.invite(form.getDisplayName(), form.getEmail(), form.getRoles(), actor);
				redirect.addFlashAttribute(Flash.ATTRIBUTE, Flash.success("Invite sent to " + User.normalizeEmail(form.getEmail()))
					.withText("The link works once, for 48 hours. You can send a new one from this page."));
				return "redirect:" + LIST + "/" + id;
			}
			catch (BusinessRuleException rule) {
				rule.addTo(errors);
			}
			catch (MailException notSent) {
				model.addAttribute(Flash.ATTRIBUTE, Flash.danger("We couldn’t send the invite email, so nobody was added.")
					.withText("Check the mail server settings and try again."));
			}
		}
		return inviteView(model);
	}

	@GetMapping("/{id}")
	String edit(@PathVariable long id, Authentication actor, Model model) {
		Detail account = service.detail(id, actor);
		UserForm form = new UserForm();
		form.setDisplayName(account.name());
		form.setRoles(account.roles().isEmpty() ? EnumSet.noneOf(Role.class) : EnumSet.copyOf(account.roles()));
		model.addAttribute("form", form);
		return editView(account, model);
	}

	@PostMapping("/{id}")
	String update(@PathVariable long id, @Valid @ModelAttribute("form") UserForm form, BindingResult errors,
			Authentication actor, Model model, RedirectAttributes redirect) {
		checkRoles(form, errors);
		if (!errors.hasErrors()) {
			try {
				Saved saved = service.update(id, form.getDisplayName(), form.getRoles(), actor);
				Flash flash = Flash.success("Saved changes for " + saved.name());
				if (saved.rolesChanged()) {
					flash = flash.withText(saved.self() ? "Your new roles apply the next time you sign in."
							: "Their open sessions have ended, so the new roles apply when they next sign in.");
				}
				redirect.addFlashAttribute(Flash.ATTRIBUTE, flash);
				return "redirect:" + LIST + "/" + id;
			}
			catch (BusinessRuleException rule) {
				rule.addTo(errors);
			}
		}
		return editView(service.detail(id, actor), model);
	}

	@PostMapping("/{id}/disable")
	String disable(@PathVariable long id, Authentication actor, RedirectAttributes redirect) {
		try {
			String name = service.disable(id, actor);
			redirect.addFlashAttribute(Flash.ATTRIBUTE, Flash.success(name + " can no longer sign in")
				.withText("Their open sessions have ended. Enable the account to give access back."));
		}
		catch (BusinessRuleException rule) {
			redirect.addFlashAttribute(Flash.ATTRIBUTE, Flash.danger(rule.getMessage()));
		}
		return "redirect:" + LIST + "/" + id;
	}

	@PostMapping("/{id}/enable")
	String enable(@PathVariable long id, RedirectAttributes redirect) {
		String name = service.enable(id);
		redirect.addFlashAttribute(Flash.ATTRIBUTE, Flash.success(name + " can sign in again"));
		return "redirect:" + LIST + "/" + id;
	}

	@PostMapping("/{id}/invite")
	String resendInvite(@PathVariable long id, Authentication actor, RedirectAttributes redirect) {
		try {
			String email = service.resendInvite(id, actor);
			redirect.addFlashAttribute(Flash.ATTRIBUTE,
					Flash.success("New invite sent to " + email).withText("The earlier link no longer works."));
		}
		catch (BusinessRuleException rule) {
			redirect.addFlashAttribute(Flash.ATTRIBUTE, Flash.danger(rule.getMessage()));
		}
		catch (MailException notSent) {
			redirect.addFlashAttribute(Flash.ATTRIBUTE, Flash.danger("We couldn’t send the invite email.")
				.withText("The earlier link still works. Check the mail server settings and try again."));
		}
		return "redirect:" + LIST + "/" + id;
	}

	/** The role rules up front, so every field's error shows in one pass; the service checks them again. */
	private static void checkRoles(UserForm form, BindingResult errors) {
		try {
			UserAdminService.checkRoles(form.getRoles());
		}
		catch (BusinessRuleException rule) {
			rule.addTo(errors);
		}
	}

	private static String inviteView(Model model) {
		model.addAttribute("allRoles", Role.values());
		return "admin/users/invite";
	}

	private static String editView(Detail account, Model model) {
		model.addAttribute("account", account);
		model.addAttribute("allRoles", Role.values());
		return "admin/users/edit";
	}

	/** A role from the filter, or null for "all roles" and for anything that is not a role. */
	private static Role parseRole(String role) {
		for (Role candidate : Role.values()) {
			if (candidate.name().equals(role)) {
				return candidate;
			}
		}
		return null;
	}

	/** Name and roles, as on the edit page. JavaBean accessors for binding; input is trimmed. */
	public static class UserForm {

		@NotBlank(message = "Enter the person’s full name.")
		@Size(max = 120, message = "Use at most 120 characters.")
		private String displayName;

		private Set<Role> roles = EnumSet.noneOf(Role.class);

		public String getDisplayName() {
			return displayName;
		}

		public void setDisplayName(String displayName) {
			this.displayName = (displayName != null) ? displayName.strip() : null;
		}

		public Set<Role> getRoles() {
			return roles;
		}

		public void setRoles(Set<Role> roles) {
			this.roles = (roles != null) ? roles : EnumSet.noneOf(Role.class);
		}

	}

	/** The invite form adds the work e-mail they will sign in with. */
	public static class InviteForm extends UserForm {

		@NotBlank(message = "Enter their work email.")
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
