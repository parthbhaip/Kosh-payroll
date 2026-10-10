package com.kosh.company;

import java.time.Clock;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.kosh.common.BusinessRuleException;
import com.kosh.common.IndianState;
import com.kosh.common.NotFoundException;
import com.kosh.common.Text;
import com.kosh.statutory.PfPolicy;
import com.kosh.web.Flash;

/**
 * {@code /admin/company}: the company details with the logo, and {@code /admin/company/pay-settings}. ADMIN only (URL
 * rule and service). The logo is served from here, never as a static file.
 */
@Controller
@RequestMapping("/admin/company")
class CompanyController {

	private static final String DETAILS = "/admin/company";

	private static final String PAY_SETTINGS = "/admin/company/pay-settings";

	private static final String LOGO_NOT_SAVED = "The logo wasn’t uploaded";

	private final CompanyService service;

	private final Clock clock;

	CompanyController(CompanyService service, Clock clock) {
		this.service = service;
		this.clock = clock;
	}

	// ------------------------------------------------------------------ details and logo

	@GetMapping
	String details(Model model) {
		model.addAttribute("form", ProfileForm.of(service.profile()));
		return detailsView(model);
	}

	@PostMapping
	String saveDetails(@Valid @ModelAttribute("form") ProfileForm form, BindingResult errors, Model model,
			RedirectAttributes redirect) {
		try {
			// The service checks this too; checked here as well so it shows with the form's other errors.
			CompanyService.requireDesignation(form.toProfile());
		}
		catch (BusinessRuleException rule) {
			rule.addTo(errors);
		}
		if (!errors.hasErrors()) {
			try {
				List<String> changed = service.updateProfile(form.toProfile());
				redirect.addFlashAttribute(Flash.ATTRIBUTE, changed.isEmpty()
						? Flash.info("Nothing to save").withText("The company details are as they were.")
						: Flash.success("Company details saved").withText("Changed: " + String.join(", ", changed) + "."));
				return "redirect:" + DETAILS;
			}
			catch (BusinessRuleException rule) {
				rule.addTo(errors);
			}
		}
		return detailsView(model);
	}

	@PostMapping("/logo")
	String uploadLogo(@RequestParam(name = "file", required = false) MultipartFile file, RedirectAttributes redirect) {
		try {
			boolean replaced = service.replaceLogo(file);
			redirect.addFlashAttribute(Flash.ATTRIBUTE, Flash.success(replaced ? "Logo replaced" : "Logo added")
				.withText("Payslips and salary letters generated from now on show it."));
		}
		catch (BusinessRuleException rule) {
			redirect.addFlashAttribute(Flash.ATTRIBUTE, Flash.danger(LOGO_NOT_SAVED).withText(rule.getMessage()));
		}
		return "redirect:" + DETAILS;
	}

	@PostMapping("/logo/remove")
	String removeLogo(RedirectAttributes redirect) {
		redirect.addFlashAttribute(Flash.ATTRIBUTE, service.removeLogo()
				? Flash.success("Logo removed").withText("Payslips and salary letters show the company name instead.")
				: Flash.info("There was no logo to remove"));
		return "redirect:" + DETAILS;
	}

	@GetMapping("/logo")
	ResponseEntity<Resource> logo() {
		CompanyService.Logo logo = service.logo().orElseThrow(() -> new NotFoundException("No company logo"));
		return ResponseEntity.ok()
			.contentType(MediaType.parseMediaType(logo.type().contentType()))
			.cacheControl(CacheControl.noCache().cachePrivate())
			.body(logo.file());
	}

	// ------------------------------------------------------------------ pay settings

	@GetMapping("/pay-settings")
	String paySettings(Model model) {
		model.addAttribute("form", PaySettingsForm.of(service.paySettings()));
		return paySettingsView(model);
	}

	@PostMapping("/pay-settings")
	String savePaySettings(@Valid @ModelAttribute("form") PaySettingsForm form, BindingResult errors, Model model,
			RedirectAttributes redirect) {
		List<String> cities = List.of();
		try {
			cities = PaySettings.parseCities(form.getMetroCities());
		}
		catch (BusinessRuleException rule) {
			rule.addTo(errors);
		}
		if (!errors.hasErrors()) {
			try {
				List<String> changed = service.updatePaySettings(form.toSettings(cities));
				redirect.addFlashAttribute(Flash.ATTRIBUTE, changed.isEmpty()
						? Flash.info("Nothing to save").withText("The pay settings are as they were.")
						: Flash.success("Pay settings saved")
							.withText("Changed: " + String.join(", ", changed)
									+ ". Payroll runs calculated from now on use them; finalized payslips stay as they are."));
				return "redirect:" + PAY_SETTINGS;
			}
			catch (BusinessRuleException rule) {
				rule.addTo(errors);
			}
		}
		return paySettingsView(model);
	}

	// ------------------------------------------------------------------ views

	private String detailsView(Model model) {
		model.addAttribute("states", IndianState.options());
		model.addAttribute("gaps", service.profile().gaps());
		model.addAttribute("hasLogo", service.hasLogo());
		return "admin/company/details";
	}

	private String paySettingsView(Model model) {
		PaySettings saved = service.paySettings();
		// The payroll being worked on: last month's, paid this month.
		YearMonth month = YearMonth.now(clock).minusMonths(1);
		model.addAttribute("saved", saved);
		model.addAttribute("month", month);
		model.addAttribute("prorationMethods", ProrationMethod.values());
		model.addAttribute("pfPolicies", List.of(PfPolicy.RESTRICT, PfPolicy.ACTUAL));
		model.addAttribute("payDays", days(PaySettings.FIRST_PAY_DAY, PaySettings.LAST_PAY_DAY, false));
		model.addAttribute("cutoffDays", days(1, PaySettings.LAST_CUTOFF_DAY, false));
		model.addAttribute("dueDays", days(1, 30, true));
		return "admin/company/pay-settings";
	}

	/** {@code first} to {@code last}, plus "Last day of the month" (31) for due days. */
	private static Map<Integer, String> days(int first, int last, boolean withLastDay) {
		Map<Integer, String> days = new LinkedHashMap<>();
		for (int day = first; day <= last; day++) {
			days.put(day, PaySettings.ordinal(day));
		}
		if (withLastDay) {
			days.put(PaySettings.LAST_DAY, PaySettings.dueDayLabel(PaySettings.LAST_DAY));
		}
		return days;
	}

	/** The company details form. JavaBean accessors for binding. */
	public static class ProfileForm {

		@NotBlank(message = "Enter the company’s legal name.")
		@Size(max = CompanyProfile.MAX_LEGAL_NAME,
				message = "Use at most " + CompanyProfile.MAX_LEGAL_NAME + " characters.")
		private String legalName;

		@Size(max = CompanyProfile.MAX_BRAND_NAME,
				message = "Use at most " + CompanyProfile.MAX_BRAND_NAME + " characters.")
		private String brandName;

		@NotBlank(message = "Enter the registered address.")
		@Size(max = CompanyProfile.MAX_ADDRESS_LINE,
				message = "Use at most " + CompanyProfile.MAX_ADDRESS_LINE + " characters.")
		private String addressLine1;

		@Size(max = CompanyProfile.MAX_ADDRESS_LINE,
				message = "Use at most " + CompanyProfile.MAX_ADDRESS_LINE + " characters.")
		private String addressLine2;

		@NotBlank(message = "Enter the city.")
		@Size(max = CompanyProfile.MAX_CITY,
				message = "Use at most " + CompanyProfile.MAX_CITY + " characters.")
		private String city;

		@NotNull(message = "Choose the state.")
		private IndianState state;

		@NotBlank(message = "Enter the PIN code.")
		@Pattern(regexp = CompanyProfile.PINCODE_FORMAT, message = "Enter a 6-digit PIN code.")
		private String pincode;

		@NotBlank(message = "Enter the company’s PAN.")
		@Pattern(regexp = CompanyProfile.PAN_FORMAT, message = "Enter a PAN like ABCDE1234F.")
		private String pan;

		@NotBlank(message = "Enter the TAN.")
		@Pattern(regexp = CompanyProfile.TAN_FORMAT, message = "Enter a TAN like PNEP12345C.")
		private String tan;

		@Pattern(regexp = CompanyProfile.PF_CODE_FORMAT, message = "Enter the 15-character code, like MHKOP0012345000.")
		private String pfEstablishmentCode;

		@Pattern(regexp = CompanyProfile.ESI_CODE_FORMAT, message = "Enter the 17-digit employer code.")
		private String esiEmployerCode;

		@Pattern(regexp = CompanyProfile.PTRC_FORMAT, message = "Enter 11 digits followed by P, like 27531234567P.")
		private String ptRegistrationNumber;

		@Size(max = CompanyProfile.MAX_SIGNATORY_NAME,
				message = "Use at most " + CompanyProfile.MAX_SIGNATORY_NAME + " characters.")
		private String signatoryName;

		@Size(max = CompanyProfile.MAX_DESIGNATION,
				message = "Use at most " + CompanyProfile.MAX_DESIGNATION + " characters.")
		private String signatoryDesignation;

		static ProfileForm of(CompanyProfile profile) {
			ProfileForm form = new ProfileForm();
			form.legalName = profile.legalName();
			form.brandName = profile.brandName();
			form.addressLine1 = profile.addressLine1();
			form.addressLine2 = profile.addressLine2();
			form.city = profile.city();
			form.state = profile.state();
			form.pincode = profile.pincode();
			form.pan = profile.pan();
			form.tan = profile.tan();
			form.pfEstablishmentCode = profile.pfEstablishmentCode();
			form.esiEmployerCode = profile.esiEmployerCode();
			form.ptRegistrationNumber = profile.ptRegistrationNumber();
			form.signatoryName = profile.signatoryName();
			form.signatoryDesignation = profile.signatoryDesignation();
			return form;
		}

		CompanyProfile toProfile() {
			return new CompanyProfile(legalName, brandName, addressLine1, addressLine2, city, state, pincode, pan, tan,
					pfEstablishmentCode, esiEmployerCode, ptRegistrationNumber, signatoryName, signatoryDesignation);
		}

		public String getLegalName() {
			return legalName;
		}

		public void setLegalName(String legalName) {
			this.legalName = Text.clean(legalName);
		}

		public String getBrandName() {
			return brandName;
		}

		public void setBrandName(String brandName) {
			this.brandName = Text.clean(brandName);
		}

		public String getAddressLine1() {
			return addressLine1;
		}

		public void setAddressLine1(String addressLine1) {
			this.addressLine1 = Text.clean(addressLine1);
		}

		public String getAddressLine2() {
			return addressLine2;
		}

		public void setAddressLine2(String addressLine2) {
			this.addressLine2 = Text.clean(addressLine2);
		}

		public String getCity() {
			return city;
		}

		public void setCity(String city) {
			this.city = Text.clean(city);
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
			this.pincode = Text.code(pincode);
		}

		public String getPan() {
			return pan;
		}

		public void setPan(String pan) {
			this.pan = Text.code(pan);
		}

		public String getTan() {
			return tan;
		}

		public void setTan(String tan) {
			this.tan = Text.code(tan);
		}

		public String getPfEstablishmentCode() {
			return pfEstablishmentCode;
		}

		public void setPfEstablishmentCode(String pfEstablishmentCode) {
			this.pfEstablishmentCode = Text.code(pfEstablishmentCode);
		}

		public String getEsiEmployerCode() {
			return esiEmployerCode;
		}

		public void setEsiEmployerCode(String esiEmployerCode) {
			this.esiEmployerCode = Text.code(esiEmployerCode);
		}

		public String getPtRegistrationNumber() {
			return ptRegistrationNumber;
		}

		public void setPtRegistrationNumber(String ptRegistrationNumber) {
			this.ptRegistrationNumber = Text.code(ptRegistrationNumber);
		}

		public String getSignatoryName() {
			return signatoryName;
		}

		public void setSignatoryName(String signatoryName) {
			this.signatoryName = Text.clean(signatoryName);
		}

		public String getSignatoryDesignation() {
			return signatoryDesignation;
		}

		public void setSignatoryDesignation(String signatoryDesignation) {
			this.signatoryDesignation = Text.clean(signatoryDesignation);
		}

	}

	/** The pay settings form. Metro cities are typed as one text, parsed by {@link PaySettings#parseCities}. */
	public static class PaySettingsForm {

		@NotNull(message = "Choose how to prorate.")
		private ProrationMethod prorationMethod;

		@NotNull(message = "Choose a pay day.")
		@Min(value = PaySettings.FIRST_PAY_DAY, message = PaySettings.PAY_DAY_RANGE)
		@Max(value = PaySettings.LAST_PAY_DAY, message = PaySettings.PAY_DAY_RANGE)
		private Integer payDay;

		@NotNull(message = "Choose the attendance cut-off.")
		@Min(value = 1, message = PaySettings.CUTOFF_RANGE)
		@Max(value = PaySettings.LAST_CUTOFF_DAY, message = PaySettings.CUTOFF_RANGE)
		private Integer attendanceCutoffDay;

		@NotNull(message = "Choose the default PF policy.")
		private PfPolicy defaultPfPolicy;

		private boolean gratuityInCtc;

		private boolean pfChargesInCtc;

		@NotNull(message = "Enter the divisor.")
		@Min(value = PaySettings.MIN_DIVISOR, message = PaySettings.DIVISOR_RANGE)
		@Max(value = PaySettings.MAX_DIVISOR, message = PaySettings.DIVISOR_RANGE)
		private Integer leaveEncashmentDivisor;

		private String metroCities;

		@NotNull(message = "Choose the PF due day.")
		@Min(value = 1, message = PaySettings.DUE_DAY_RANGE)
		@Max(value = PaySettings.LAST_DAY, message = PaySettings.DUE_DAY_RANGE)
		private Integer pfDueDay;

		@NotNull(message = "Choose the ESI due day.")
		@Min(value = 1, message = PaySettings.DUE_DAY_RANGE)
		@Max(value = PaySettings.LAST_DAY, message = PaySettings.DUE_DAY_RANGE)
		private Integer esiDueDay;

		@NotNull(message = "Choose the TDS due day.")
		@Min(value = 1, message = PaySettings.DUE_DAY_RANGE)
		@Max(value = PaySettings.LAST_DAY, message = PaySettings.DUE_DAY_RANGE)
		private Integer tdsDueDay;

		@NotNull(message = "Choose the PT due day.")
		@Min(value = 1, message = PaySettings.DUE_DAY_RANGE)
		@Max(value = PaySettings.LAST_DAY, message = PaySettings.DUE_DAY_RANGE)
		private Integer ptDueDay;

		static PaySettingsForm of(PaySettings settings) {
			PaySettingsForm form = new PaySettingsForm();
			form.prorationMethod = settings.prorationMethod();
			form.payDay = settings.payDay();
			form.attendanceCutoffDay = settings.attendanceCutoffDay();
			form.defaultPfPolicy = settings.defaultPfPolicy();
			form.gratuityInCtc = settings.gratuityInCtc();
			form.pfChargesInCtc = settings.pfChargesInCtc();
			form.leaveEncashmentDivisor = settings.leaveEncashmentDivisor();
			form.metroCities = String.join(", ", settings.metroCities());
			form.pfDueDay = settings.pfDueDay();
			form.esiDueDay = settings.esiDueDay();
			form.tdsDueDay = settings.tdsDueDay();
			form.ptDueDay = settings.ptDueDay();
			return form;
		}

		PaySettings toSettings(List<String> cities) {
			return new PaySettings(prorationMethod, payDay, attendanceCutoffDay, defaultPfPolicy, gratuityInCtc,
					pfChargesInCtc, leaveEncashmentDivisor, cities, pfDueDay, esiDueDay, tdsDueDay, ptDueDay);
		}

		public ProrationMethod getProrationMethod() {
			return prorationMethod;
		}

		public void setProrationMethod(ProrationMethod prorationMethod) {
			this.prorationMethod = prorationMethod;
		}

		public Integer getPayDay() {
			return payDay;
		}

		public void setPayDay(Integer payDay) {
			this.payDay = payDay;
		}

		public Integer getAttendanceCutoffDay() {
			return attendanceCutoffDay;
		}

		public void setAttendanceCutoffDay(Integer attendanceCutoffDay) {
			this.attendanceCutoffDay = attendanceCutoffDay;
		}

		public PfPolicy getDefaultPfPolicy() {
			return defaultPfPolicy;
		}

		public void setDefaultPfPolicy(PfPolicy defaultPfPolicy) {
			this.defaultPfPolicy = defaultPfPolicy;
		}

		public boolean isGratuityInCtc() {
			return gratuityInCtc;
		}

		public void setGratuityInCtc(boolean gratuityInCtc) {
			this.gratuityInCtc = gratuityInCtc;
		}

		public boolean isPfChargesInCtc() {
			return pfChargesInCtc;
		}

		public void setPfChargesInCtc(boolean pfChargesInCtc) {
			this.pfChargesInCtc = pfChargesInCtc;
		}

		public Integer getLeaveEncashmentDivisor() {
			return leaveEncashmentDivisor;
		}

		public void setLeaveEncashmentDivisor(Integer leaveEncashmentDivisor) {
			this.leaveEncashmentDivisor = leaveEncashmentDivisor;
		}

		public String getMetroCities() {
			return metroCities;
		}

		public void setMetroCities(String metroCities) {
			this.metroCities = metroCities;
		}

		public Integer getPfDueDay() {
			return pfDueDay;
		}

		public void setPfDueDay(Integer pfDueDay) {
			this.pfDueDay = pfDueDay;
		}

		public Integer getEsiDueDay() {
			return esiDueDay;
		}

		public void setEsiDueDay(Integer esiDueDay) {
			this.esiDueDay = esiDueDay;
		}

		public Integer getTdsDueDay() {
			return tdsDueDay;
		}

		public void setTdsDueDay(Integer tdsDueDay) {
			this.tdsDueDay = tdsDueDay;
		}

		public Integer getPtDueDay() {
			return ptDueDay;
		}

		public void setPtDueDay(Integer ptDueDay) {
			this.ptDueDay = ptDueDay;
		}

	}

}
