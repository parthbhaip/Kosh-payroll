package com.kosh.web;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Reference page for the design system: every component and state from {@code static/css/app.css} on one page, with
 * figures formatted by {@code @fmt} like real pages. The controller exists only under the {@code dev} profile, so every
 * other profile answers 404. The figures are display samples, not payroll calculations.
 */
@Controller
@Profile("dev")
class UiKitController {

	/** Every symbol in {@code static/img/icons.svg}, in sprite order (ids are {@code i-<name>}). */
	static final List<String> ICONS = List.of("dashboard", "users", "user", "building", "calendar", "clock", "wallet",
			"receipt", "file", "file-text", "download", "upload", "check", "x", "plus", "minus", "edit", "trash", "search",
			"filter", "bell", "settings", "shield", "lock", "bank", "chart", "briefcase", "logout", "chevron-down",
			"chevron-right", "menu", "alert", "info", "rupee", "send");

	private static final List<SampleRow> ROWS = List.of(
			new SampleRow("KSH-0003", "Meera Deshpande", "Finance", "Rajarampuri", "ACTIVE", "Active", "96500.00",
					"14862.00", "0.00"),
			new SampleRow("KSH-0007", "Sandeep Patil", "Production", "MIDC Shiroli", "ACTIVE", "Active", "34200.00",
					"4304.00", "1850.00"),
			new SampleRow("KSH-0012", "Aarti Kulkarni", "Quality", "MIDC Shiroli", "ON_NOTICE", "On notice", "41750.00",
					"5210.00", "-2500.00"),
			new SampleRow("KSH-0018", "Farhan Mulla", "Sales", "Pune", "ACTIVE", "Active", "58000.00", "7920.00", "0.00"),
			new SampleRow("KSH-0021", "Sneha Shinde", "Human Resources", "Rajarampuri", "ACTIVE", "Active", "45300.00",
					"5686.00", "0.00"),
			new SampleRow("KSH-0026", "Rohit Jadhav", "Production", "MIDC Shiroli", "EXITED", "Exited", "22450.00",
					"2894.00", "-1250.00"));

	@GetMapping("/dev/ui-kit")
	String uiKit(Model model) {
		model.addAttribute("icons", ICONS);
		model.addAttribute("rows", ROWS);
		model.addAttribute("totals", SampleRow.total(ROWS));
		model.addAttribute("monthNet", new BigDecimal("6248315.50"));
		model.addAttribute("statutoryDue", new BigDecimal("987640.00"));
		return "dev/ui-kit";
	}

	/** One line of the sample payroll register. Net pay = gross - deductions + adjustment. */
	public record SampleRow(String code, String name, String department, String location, String status, String statusLabel,
			BigDecimal gross, BigDecimal deductions, BigDecimal adjustment) {

		SampleRow(String code, String name, String department, String location, String status, String statusLabel,
				String gross, String deductions, String adjustment) {
			this(code, name, department, location, status, statusLabel, new BigDecimal(gross),
					new BigDecimal(deductions), new BigDecimal(adjustment));
		}

		public BigDecimal net() {
			return gross.subtract(deductions).add(adjustment);
		}

		static SampleRow total(List<SampleRow> rows) {
			return new SampleRow(null, "Total", null, null, null, null,
					rows.stream().map(SampleRow::gross).reduce(BigDecimal.ZERO, BigDecimal::add),
					rows.stream().map(SampleRow::deductions).reduce(BigDecimal.ZERO, BigDecimal::add),
					rows.stream().map(SampleRow::adjustment).reduce(BigDecimal.ZERO, BigDecimal::add));
		}

	}

}
