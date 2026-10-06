package com.kosh.web;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.springframework.boot.test.context.TestComponent;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Test-only pages on real menu paths, so the app layout and the shared fragments can be rendered before the real pages
 * exist. {@code @TestComponent} keeps component scanning away; test classes {@code @Import} it.
 */
@TestComponent
@Controller
class ShellTestPages {

	static final List<String> PAGES = List.of("/hr/employees", "/payroll/runs", "/me/payslips", "/me/profile",
			"/admin/users", "/notifications");

	// Area homes such as /hr are real pages (HomeController).
	@GetMapping({ "/hr/employees", "/hr/employees/{id}", "/payroll/runs", "/me/payslips", "/me/profile",
			"/admin/users", "/notifications" })
	String appPage() {
		return "test-only/app-page";
	}

	@GetMapping("/test-only/fragments")
	String fragments(@PageableDefault(size = 25) Pageable pageable, Model model) {
		ProbeForm form = new ProbeForm();
		form.setEmail("not-an-email");
		form.setDepartment("QA");
		BindingResult errors = new BeanPropertyBindingResult(form, "form");
		errors.rejectValue("email", "email", "Enter a work email like name@company.in.");
		model.addAttribute("form", form);
		model.addAttribute(BindingResult.MODEL_KEY_PREFIX + "form", errors);

		Map<String, String> departments = new LinkedHashMap<>();
		departments.put("PROD", "Production");
		departments.put("QA", "Quality");
		departments.put("HRA", "HR & Admin");
		model.addAttribute("departments", departments);

		List<String> rows = IntStream.rangeClosed(1, pageable.getPageSize()).mapToObj(n -> "Employee " + n).toList();
		model.addAttribute("page", new PageImpl<>(rows, pageable, 500));
		model.addAttribute("net", new BigDecimal("81638.00"));
		model.addAttribute("adjustment", new BigDecimal("-1250.00"));
		model.addAttribute("steps", List.of("Personal", "Job", "Statutory", "Bank", "Review"));
		model.addAttribute("timeline",
				List.of(new Event("Draft created", "Priya Joshi, Payroll · 25 Sep 2026, 10:12 AM", null, null),
						new Event("Rejected by Finance", "Nikhil Pawar, Finance · 27 Sep 2026, 11:05 AM",
								"Overtime for the Shiroli second shift is missing.", "danger")));
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("Employee code", "KSH-0003");
		details.put("UAN", null);
		model.addAttribute("details", details);
		return "test-only/fragments-page";
	}

	@GetMapping("/test-only/toast")
	String toast(Model model) {
		model.addAttribute(Flash.ATTRIBUTE, Flash.success("Draft saved").withText("Salary structure for KSH-0003."));
		return "test-only/toast";
	}

	record Event(String title, String meta, String body, String tone) {

	}

	/** A form-backing bean with JavaBean accessors, as Spring binding needs. */
	public static class ProbeForm {

		private String email;

		private String department;

		private String note;

		public String getEmail() {
			return email;
		}

		public void setEmail(String email) {
			this.email = email;
		}

		public String getDepartment() {
			return department;
		}

		public void setDepartment(String department) {
			this.department = department;
		}

		public String getNote() {
			return note;
		}

		public void setNote(String note) {
			this.note = note;
		}

	}

}
