package com.kosh.employee;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.convert.ConversionService;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.mail.MailException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.ServletRequestDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.kosh.common.BusinessRuleException;
import com.kosh.common.Gender;
import com.kosh.common.IndianState;
import com.kosh.common.NotFoundException;
import com.kosh.employee.EmployeeService.Created;
import com.kosh.employee.EmployeeService.Problem;
import com.kosh.employee.EmployeeService.Saved;
import com.kosh.statutory.PfPolicy;
import com.kosh.web.Flash;

/**
 * {@code /hr/employees/new} and {@code /hr/employees/{id}/edit}: the five-step add and edit form (PLAN T17). HR and
 * ADMIN only (URL rule and service). The form is a draft in the HTTP session, one per employee being edited and one
 * for a new employee, so HR can leave and come back to it until they save or discard it. Each step page binds only its
 * own fields, is checked on Continue and opens the next step; a new employee's later steps open only in order.
 */
@Controller
@RequestMapping("/hr/employees")
class EmployeeEditorController {

	private static final String BASE = "/hr/employees/";

	private static final String NEW = BASE + "new";

	private static final String DRAFT = "employee-draft:";

	private final EmployeeService service;

	private final ConversionService conversion;

	EmployeeEditorController(EmployeeService service, @Qualifier("mvcConversionService") ConversionService conversion) {
		this.service = service;
		this.conversion = conversion;
	}

	// ------------------------------------------------------------------ add

	/** Starts a draft, or picks up the one already open in this session at the furthest step reached. */
	@GetMapping("/new")
	String start(HttpSession session, RedirectAttributes redirect) {
		EmployeeForm draft = draft(session, null);
		if (draft == null) {
			store(session, service.newDraft());
			return "redirect:" + NEW + "/" + EmployeeStep.PERSONAL.slug();
		}
		redirect.addFlashAttribute(Flash.ATTRIBUTE, Flash.info("Continuing the employee you started adding")
			.withText("Your answers so far are kept. Discard the draft to start again."));
		return "redirect:" + NEW + "/" + draft.getReached().slug();
	}

	@GetMapping("/new/{slug}")
	String newStep(@PathVariable String slug, HttpSession session, Authentication viewer, Model model) {
		return show(null, step(slug), session, viewer, model);
	}

	@PostMapping("/new/{slug}")
	String newStepContinue(@PathVariable String slug, HttpServletRequest request, HttpSession session,
			Authentication viewer, Model model, RedirectAttributes redirect) {
		return proceed(null, step(slug), request, session, viewer, model, redirect);
	}

	@PostMapping("/new")
	String create(HttpServletRequest request, HttpSession session, Authentication actor, Model model,
			RedirectAttributes redirect) {
		EmployeeForm draft = draft(session, null);
		if (draft == null || !draft.canOpen(EmployeeStep.REVIEW)) {
			return reopen(null, draft, redirect);
		}
		BindingResult errors = bind(draft, EmployeeStep.REVIEW, request);
		store(session, draft);
		if (!ready(draft, errors)) {
			return view(EmployeeStep.REVIEW, draft, errors, actor, model);
		}
		try {
			Created created = service.create(draft, actor);
			session.removeAttribute(DRAFT + "new");
			redirect.addFlashAttribute(Flash.ATTRIBUTE,
					Flash.success("%s added as %s".formatted(created.name(), created.code()))
						.withText((created.invitedEmail() != null) ? invited(created.invitedEmail())
								: "No sign-in account yet. You can create one from Edit at any time."));
			return "redirect:" + BASE + created.id();
		}
		catch (BusinessRuleException rule) {
			show(rule, errors);
		}
		catch (MailException notSent) {
			model.addAttribute(Flash.ATTRIBUTE, Flash.danger("We couldn’t send the invite email, so the employee wasn’t added.")
				.withText("Untick the sign-in account to add them without one, or check the mail server settings and try again."));
		}
		return view(EmployeeStep.REVIEW, draft, errors, actor, model);
	}

	@PostMapping("/new/discard")
	String discard(HttpSession session, RedirectAttributes redirect) {
		if (session.getAttribute(DRAFT + "new") != null) {
			session.removeAttribute(DRAFT + "new");
			redirect.addFlashAttribute(Flash.ATTRIBUTE, Flash.info("Draft discarded").withText("Nothing was saved."));
		}
		return "redirect:/hr/employees";
	}

	// ------------------------------------------------------------------ edit

	/**
	 * Starts an edit from the record as it is now (any earlier edit draft of this employee is replaced) and opens the
	 * step asked for, Personal by default: the profile's Edit links open the step for the tab they sit on.
	 */
	@GetMapping("/{id}/edit")
	String edit(@PathVariable long id, @RequestParam(required = false) String step, HttpSession session,
			Authentication viewer) {
		store(session, service.draftFor(id, viewer));
		EmployeeStep first = EmployeeStep.fromSlug(step).orElse(EmployeeStep.PERSONAL);
		return "redirect:" + BASE + id + "/edit/" + first.slug();
	}

	@GetMapping("/{id}/edit/{slug}")
	String editStep(@PathVariable long id, @PathVariable String slug, HttpSession session, Authentication viewer,
			Model model) {
		return show(id, step(slug), session, viewer, model);
	}

	@PostMapping("/{id}/edit/{slug}")
	String editStepContinue(@PathVariable long id, @PathVariable String slug, HttpServletRequest request,
			HttpSession session, Authentication viewer, Model model, RedirectAttributes redirect) {
		return proceed(id, step(slug), request, session, viewer, model, redirect);
	}

	@PostMapping("/{id}/edit")
	String update(@PathVariable long id, HttpServletRequest request, HttpSession session, Authentication actor,
			Model model, RedirectAttributes redirect) {
		EmployeeForm draft = draft(session, id);
		if (draft == null) {
			return reopen(id, null, redirect);
		}
		BindingResult errors = bind(draft, EmployeeStep.REVIEW, request);
		store(session, draft);
		if (!ready(draft, errors)) {
			return view(EmployeeStep.REVIEW, draft, errors, actor, model);
		}
		try {
			Saved saved = service.update(id, draft, actor);
			session.removeAttribute(DRAFT + id);
			Flash flash = saved.changed().isEmpty()
					? Flash.info("Nothing to save").withText(saved.name() + "’s record is as it was.")
					: Flash.success("Saved " + saved.name())
						.withText("Changed: " + String.join(", ", saved.changed()) + "."
								+ ((saved.invitedEmail() != null) ? " " + invited(saved.invitedEmail()) : ""));
			redirect.addFlashAttribute(Flash.ATTRIBUTE, flash);
			return "redirect:" + BASE + id;
		}
		catch (OptimisticLockingFailureException stale) {
			session.removeAttribute(DRAFT + id);
			redirect.addFlashAttribute(Flash.ATTRIBUTE, Flash.danger("Your changes weren’t saved")
				.withText("Someone else saved changes to this record while you were editing it. This is the record as it is now: edit it again to make yours."));
			return "redirect:" + BASE + id;
		}
		catch (BusinessRuleException rule) {
			show(rule, errors);
		}
		catch (MailException notSent) {
			model.addAttribute(Flash.ATTRIBUTE, Flash.danger("We couldn’t send the invite email, so nothing was saved.")
				.withText("Untick the sign-in account to save the changes without one, or check the mail server settings and try again."));
		}
		return view(EmployeeStep.REVIEW, draft, errors, actor, model);
	}

	// ------------------------------------------------------------------ the steps

	private String show(Long id, EmployeeStep step, HttpSession session, Authentication viewer, Model model) {
		EmployeeForm draft = draft(session, id);
		if (draft == null || !draft.canOpen(step)) {
			return reopen(id, draft, null);
		}
		if (step == EmployeeStep.REVIEW && !draft.isEditing() && draft.getLoginEmail() == null) {
			// The personal e-mail is the usual sign-in address; HR can change it here.
			draft.setLoginEmail(draft.getPersonalEmail());
			store(session, draft);
		}
		return view(step, draft, null, viewer, model);
	}

	/** Binds the step's fields; with no problems, opens the next step, else shows this one with the problems. */
	private String proceed(Long id, EmployeeStep step, HttpServletRequest request, HttpSession session,
			Authentication viewer, Model model, RedirectAttributes redirect) {
		if (step == EmployeeStep.REVIEW) {
			throw new NotFoundException("Review is saved with POST " + base(id));
		}
		EmployeeForm draft = draft(session, id);
		if (draft == null || !draft.canOpen(step)) {
			return reopen(id, draft, redirect);
		}
		BindingResult errors = bind(draft, step, request);
		for (Problem problem : service.problems(draft, step)) {
			if (!errors.hasFieldErrors(problem.field())) {
				errors.rejectValue(problem.field(), "rule", problem.message());
			}
		}
		if (errors.hasErrors()) {
			store(session, draft);
			return view(step, draft, errors, viewer, model);
		}
		draft.tidy();
		draft.reach(step.next());
		store(session, draft);
		return "redirect:" + base(id) + "/" + step.next().slug();
	}

	/**
	 * Whether the draft can be saved: the review fields (sign-in account) are put next to their fields; problems on
	 * other steps are listed on the review page by {@link #view}.
	 */
	private boolean ready(EmployeeForm draft, BindingResult errors) {
		boolean ready = !errors.hasErrors();
		for (Problem problem : service.problems(draft, null)) {
			if (problem.step() == EmployeeStep.REVIEW && !errors.hasFieldErrors(problem.field())) {
				errors.rejectValue(problem.field(), "rule", problem.message());
			}
			ready = false;
		}
		return ready;
	}

	private String view(EmployeeStep step, EmployeeForm draft, BindingResult errors, Authentication viewer,
			Model model) {
		model.addAttribute("form", draft);
		if (errors != null) {
			model.addAttribute(BindingResult.MODEL_KEY_PREFIX + "form", errors);
		}
		model.addAttribute("step", step);
		model.addAttribute("steps", EmployeeStep.labels());
		model.addAttribute("base", base(draft.getId()));
		model.addAttribute("options", service.options(draft));
		model.addAttribute("fullPan", EmployeeService.seesFullPan(viewer));
		model.addAttribute("states", IndianState.options());
		model.addAttribute("genders", Gender.values());
		model.addAttribute("types", types());
		model.addAttribute("pfPolicies", PfPolicy.values());
		if (step == EmployeeStep.REVIEW) {
			Map<EmployeeStep, List<Problem>> problems = new EnumMap<>(EmployeeStep.class);
			for (Problem problem : service.problems(draft, null)) {
				if (problem.step() != null && problem.step() != EmployeeStep.REVIEW) {
					problems.computeIfAbsent(problem.step(), ignored -> new ArrayList<>()).add(problem);
				}
			}
			model.addAttribute("problems", problems);
			model.addAttribute("problemCount", problems.values().stream().mapToInt(List::size).sum());
		}
		return "hr/employees/editor";
	}

	private BindingResult bind(EmployeeForm draft, EmployeeStep step, HttpServletRequest request) {
		ServletRequestDataBinder binder = new ServletRequestDataBinder(draft, "form");
		binder.setConversionService(conversion);
		binder.setAllowedFields(step.fields().toArray(String[]::new));
		binder.bind(request);
		return binder.getBindingResult();
	}

	/** A refused save: next to the sign-in field it is about, else at the top of the review page. */
	private static void show(BusinessRuleException rule, BindingResult errors) {
		if (rule.getField() != null && EmployeeStep.of(rule.getField()) == EmployeeStep.REVIEW) {
			rule.addTo(errors);
		}
		else {
			errors.reject("rule", rule.getMessage());
		}
	}

	/**
	 * Back to where the draft is: the furthest step reached, or a fresh start when the session holds no draft (it ended
	 * with the session, or was saved in another tab).
	 */
	private static String reopen(Long id, EmployeeForm draft, RedirectAttributes redirect) {
		if (draft != null) {
			return "redirect:" + base(id) + "/" + draft.getReached().slug();
		}
		if (redirect != null) {
			redirect.addFlashAttribute(Flash.ATTRIBUTE, Flash.warning("That form had closed, so nothing was saved")
				.withText("Forms are kept until they are saved or you sign out. Start again here."));
		}
		return "redirect:" + ((id == null) ? NEW : BASE + id + "/edit");
	}

	private static String invited(String email) {
		return "Sign-in invite sent to %s. The link works once, for 48 hours.".formatted(email);
	}

	private static Map<String, String> types() {
		Map<String, String> types = new LinkedHashMap<>();
		for (EmploymentType type : EmploymentType.values()) {
			types.put(type.name(), type.label());
		}
		return types;
	}

	private static EmployeeForm draft(HttpSession session, Long id) {
		return (session.getAttribute(DRAFT + ((id == null) ? "new" : id)) instanceof EmployeeForm form) ? form : null;
	}

	/** Puts the draft (back) in the session, which also marks it changed for a session store that copies values. */
	private static void store(HttpSession session, EmployeeForm draft) {
		session.setAttribute(DRAFT + ((draft.getId() == null) ? "new" : draft.getId()), draft);
	}

	private static String base(Long id) {
		return (id == null) ? NEW : BASE + id + "/edit";
	}

	private static EmployeeStep step(String slug) {
		return EmployeeStep.fromSlug(slug).orElseThrow(() -> new NotFoundException("No step " + slug));
	}

}
