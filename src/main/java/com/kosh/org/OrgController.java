package com.kosh.org;

import java.util.Set;

import org.springframework.data.domain.Sort;
import org.springframework.data.web.SortDefault;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.kosh.common.BusinessRuleException;
import com.kosh.common.IndianState;
import com.kosh.common.NotFoundException;
import com.kosh.org.OrgService.Item;
import com.kosh.org.OrgService.Problem;
import com.kosh.org.OrgService.Saved;
import com.kosh.web.Flash;

/**
 * {@code /hr/org/{kind}}: the five lists of the organisation structure, with add, edit, deactivate, activate and
 * delete. HR and ADMIN only (URL rule and service).
 */
@Controller
@RequestMapping("/hr/org")
class OrgController {

	private static final String BASE = "/hr/org/";

	private static final Set<String> SORTABLE = Set.of("code", "name");

	private static final String DEACTIVATE_INSTEAD = "Deactivate it instead: it stays on the records that have it but can’t be chosen for new ones.";

	private final OrgService service;

	OrgController(OrgService service) {
		this.service = service;
	}

	@GetMapping
	String index() {
		return "redirect:" + BASE + OrgKind.DEPARTMENTS.slug();
	}

	@GetMapping("/{slug}")
	String list(@PathVariable String slug, @SortDefault("name") Sort sort, Model model) {
		OrgKind kind = kind(slug);
		// Case-insensitive so H2 (case-sensitive) and MySQL (ci collation) list in the same order.
		Sort allowed = Sort.by(sort.filter(order -> SORTABLE.contains(order.getProperty()))
			.map(Sort.Order::ignoreCase)
			.toList());
		if (allowed.isUnsorted()) {
			allowed = Sort.by(Sort.Order.asc("name").ignoreCase());
		}
		model.addAttribute("kind", kind);
		model.addAttribute("kinds", OrgKind.values());
		model.addAttribute("counts", service.counts());
		model.addAttribute("rows", service.list(kind, allowed));
		model.addAttribute("sort", allowed);
		return "hr/org/list";
	}

	@GetMapping("/{slug}/new")
	String newItem(@PathVariable String slug, Model model) {
		OrgKind kind = kind(slug);
		OrgForm form = new OrgForm();
		form.setPtApplicable(true);
		model.addAttribute("form", form);
		return formView(kind, null, model);
	}

	@PostMapping("/{slug}")
	String create(@PathVariable String slug, @ModelAttribute("form") OrgForm form, BindingResult errors, Model model,
			RedirectAttributes redirect) {
		OrgKind kind = kind(slug);
		OrgDetails details = form.toDetails();
		check(kind, null, details, errors);
		if (!errors.hasErrors()) {
			try {
				service.create(kind, details);
				redirect.addFlashAttribute(Flash.ATTRIBUTE, Flash.success(details.name() + " added"));
				return "redirect:" + BASE + kind.slug();
			}
			catch (BusinessRuleException rule) {
				rule.addTo(errors);
			}
		}
		return formView(kind, null, model);
	}

	@GetMapping("/{slug}/{id}")
	String edit(@PathVariable String slug, @PathVariable long id, Model model) {
		OrgKind kind = kind(slug);
		Item item = service.item(kind, id);
		model.addAttribute("form", OrgForm.of(item.details()));
		return formView(kind, item, model);
	}

	@PostMapping("/{slug}/{id}")
	String update(@PathVariable String slug, @PathVariable long id, @ModelAttribute("form") OrgForm form,
			BindingResult errors, Model model, RedirectAttributes redirect) {
		OrgKind kind = kind(slug);
		Item item = service.item(kind, id);
		OrgDetails details = form.toDetails();
		check(kind, id, details, errors);
		if (!errors.hasErrors()) {
			try {
				Saved saved = service.update(kind, id, details);
				redirect.addFlashAttribute(Flash.ATTRIBUTE, saved.changed().isEmpty()
						? Flash.info("Nothing to save").withText(saved.name() + " is as it was.")
						: Flash.success("Saved " + saved.name())
							.withText("Changed: " + String.join(", ", saved.changed()) + "."));
				return "redirect:" + BASE + kind.slug();
			}
			catch (BusinessRuleException rule) {
				rule.addTo(errors);
			}
		}
		return formView(kind, item, model);
	}

	@PostMapping("/{slug}/{id}/deactivate")
	String deactivate(@PathVariable String slug, @PathVariable long id, RedirectAttributes redirect) {
		OrgKind kind = kind(slug);
		String name = service.setActive(kind, id, false);
		redirect.addFlashAttribute(Flash.ATTRIBUTE, Flash.success(name + " deactivated")
			.withText("It stays on the records that have it but can’t be chosen for new ones."));
		return "redirect:" + BASE + kind.slug() + "/" + id;
	}

	@PostMapping("/{slug}/{id}/activate")
	String activate(@PathVariable String slug, @PathVariable long id, RedirectAttributes redirect) {
		OrgKind kind = kind(slug);
		String name = service.setActive(kind, id, true);
		redirect.addFlashAttribute(Flash.ATTRIBUTE, Flash.success(name + " is active again")
			.withText("It can be chosen for new records."));
		return "redirect:" + BASE + kind.slug() + "/" + id;
	}

	@PostMapping("/{slug}/{id}/delete")
	String delete(@PathVariable String slug, @PathVariable long id, RedirectAttributes redirect) {
		OrgKind kind = kind(slug);
		try {
			String name = service.delete(kind, id);
			redirect.addFlashAttribute(Flash.ATTRIBUTE, Flash.success(name + " deleted"));
			return "redirect:" + BASE + kind.slug();
		}
		catch (BusinessRuleException inUse) {
			redirect.addFlashAttribute(Flash.ATTRIBUTE, Flash.danger(inUse.getMessage()).withText(DEACTIVATE_INSTEAD));
			return "redirect:" + BASE + kind.slug() + "/" + id;
		}
	}

	/** Every rule broken, next to its field; a field that already failed to bind keeps that one message. */
	private void check(OrgKind kind, Long id, OrgDetails details, BindingResult errors) {
		for (Problem problem : service.problems(kind, id, details)) {
			if (!errors.hasFieldErrors(problem.field())) {
				errors.rejectValue(problem.field(), "rule", problem.message());
			}
		}
	}

	private String formView(OrgKind kind, Item item, Model model) {
		model.addAttribute("kind", kind);
		model.addAttribute("item", item);
		if (kind == OrgKind.DEPARTMENTS) {
			model.addAttribute("heads", service.headOptions((item != null) ? item.details().headUserId() : null));
		}
		if (kind == OrgKind.LOCATIONS) {
			model.addAttribute("states", IndianState.options());
		}
		return "hr/org/edit";
	}

	private static OrgKind kind(String slug) {
		return OrgKind.ofSlug(slug).orElseThrow(() -> new NotFoundException("No organisation list called " + slug));
	}

	/** The add and edit form for every kind; the service ignores the fields a kind does not have. */
	public static class OrgForm {

		private String code;

		private String name;

		private Long headUserId;

		private String addressLine1;

		private String addressLine2;

		private String city;

		private IndianState state;

		private String pincode;

		private boolean ptApplicable;

		static OrgForm of(OrgDetails details) {
			OrgForm form = new OrgForm();
			form.code = details.code();
			form.name = details.name();
			form.headUserId = details.headUserId();
			form.addressLine1 = details.addressLine1();
			form.addressLine2 = details.addressLine2();
			form.city = details.city();
			form.state = details.state();
			form.pincode = details.pincode();
			form.ptApplicable = details.ptApplicable();
			return form;
		}

		OrgDetails toDetails() {
			return new OrgDetails(code, name, headUserId, addressLine1, addressLine2, city, state, pincode,
					ptApplicable);
		}

		public String getCode() {
			return code;
		}

		public void setCode(String code) {
			this.code = code;
		}

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public Long getHeadUserId() {
			return headUserId;
		}

		public void setHeadUserId(Long headUserId) {
			this.headUserId = headUserId;
		}

		public String getAddressLine1() {
			return addressLine1;
		}

		public void setAddressLine1(String addressLine1) {
			this.addressLine1 = addressLine1;
		}

		public String getAddressLine2() {
			return addressLine2;
		}

		public void setAddressLine2(String addressLine2) {
			this.addressLine2 = addressLine2;
		}

		public String getCity() {
			return city;
		}

		public void setCity(String city) {
			this.city = city;
		}

		public IndianState getState() {
			return state;
		}

		public void setState(IndianState state) {
			this.state = state;
		}

		public String getPincode() {
			return pincode;
		}

		public void setPincode(String pincode) {
			this.pincode = pincode;
		}

		public boolean isPtApplicable() {
			return ptApplicable;
		}

		public void setPtApplicable(boolean ptApplicable) {
			this.ptApplicable = ptApplicable;
		}

	}

}
