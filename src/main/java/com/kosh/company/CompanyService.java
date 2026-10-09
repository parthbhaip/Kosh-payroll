package com.kosh.company;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.core.io.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.kosh.audit.AuditAction;
import com.kosh.audit.AuditChanges;
import com.kosh.audit.AuditService;
import com.kosh.common.BusinessRuleException;
import com.kosh.common.IndianState;
import com.kosh.statutory.PfPolicy;
import com.kosh.storage.FileStorage;
import com.kosh.storage.FileStorage.FileType;

/**
 * The company profile, logo and pay settings (PLAN T11). Anyone's code may read them (payslips, payroll, compliance
 * files); only an ADMIN changes them, and every change goes in the audit log with the fields it touched.
 */
@Service
public class CompanyService {

	static final Set<FileType> LOGO_TYPES = EnumSet.of(FileType.PNG, FileType.JPEG);

	static final String LOGO_FOLDER = "company";

	/** The logo as stored: where it is and what it is. */
	public record Logo(Resource file, FileType type) {

	}

	private final CompanyRepository companies;

	private final FileStorage storage;

	private final AuditService audit;

	public CompanyService(CompanyRepository companies, FileStorage storage, AuditService audit) {
		this.companies = companies;
		this.storage = storage;
		this.audit = audit;
	}

	@Transactional(readOnly = true)
	public CompanyProfile profile() {
		return company().profile();
	}

	@Transactional(readOnly = true)
	public PaySettings paySettings() {
		return company().paySettings();
	}

	@Transactional(readOnly = true)
	public boolean hasLogo() {
		return company().getLogoPath() != null;
	}

	/** The logo file, if one was uploaded. */
	@Transactional(readOnly = true)
	public Optional<Logo> logo() {
		return Optional.ofNullable(company().getLogoPath())
			.map(path -> new Logo(storage.load(path), FileStorage.typeOf(path)));
	}

	/**
	 * Saves the profile. The form checks the same rules first, so a person sees them all at once.
	 * @return the labels of the fields that changed, empty when nothing did
	 */
	@PreAuthorize("hasRole('ADMIN')")
	@Transactional
	public List<String> updateProfile(CompanyProfile profile) {
		requireProfile(profile);
		Company company = company();
		CompanyProfile was = company.profile();
		AuditChanges changes = AuditChanges.create()
			.field("Legal name", was.legalName(), profile.legalName())
			.field("Brand name", was.brandName(), profile.brandName())
			.field("Address line 1", was.addressLine1(), profile.addressLine1())
			.field("Address line 2", was.addressLine2(), profile.addressLine2())
			.field("City", was.city(), profile.city())
			.field("State", label(was.state()), label(profile.state()))
			.field("PIN code", was.pincode(), profile.pincode())
			.pan("PAN", was.pan(), profile.pan())
			.field("TAN", was.tan(), profile.tan())
			.field("PF establishment code", was.pfEstablishmentCode(), profile.pfEstablishmentCode())
			.field("ESI employer code", was.esiEmployerCode(), profile.esiEmployerCode())
			.field("PT registration number", was.ptRegistrationNumber(), profile.ptRegistrationNumber())
			.field("Authorised signatory", was.signatoryName(), profile.signatoryName())
			.field("Signatory designation", was.signatoryDesignation(), profile.signatoryDesignation());
		if (changes.isEmpty()) {
			return List.of();
		}
		company.setProfile(profile);
		List<String> changed = List.copyOf(changes.after().keySet());
		audit.record(AuditAction.COMPANY_DETAILS_CHANGED, Company.AUDIT_TYPE, Company.ID,
				"Changed company details: " + String.join(", ", changed), changes);
		return changed;
	}

	/** @return the labels of the settings that changed, empty when nothing did */
	@PreAuthorize("hasRole('ADMIN')")
	@Transactional
	public List<String> updatePaySettings(PaySettings settings) {
		check(settings);
		Company company = company();
		PaySettings was = company.paySettings();
		AuditChanges changes = AuditChanges.create()
			.field("Proration", was.prorationMethod().label(), settings.prorationMethod().label())
			.field("Pay day", PaySettings.ordinal(was.payDay()), PaySettings.ordinal(settings.payDay()))
			.field("Attendance cut-off", PaySettings.ordinal(was.attendanceCutoffDay()),
					PaySettings.ordinal(settings.attendanceCutoffDay()))
			.field("Default PF policy", was.defaultPfPolicy().label(), settings.defaultPfPolicy().label())
			.field("Gratuity provision in CTC", yesNo(was.gratuityInCtc()), yesNo(settings.gratuityInCtc()))
			.field("PF admin and EDLI in CTC", yesNo(was.pfChargesInCtc()), yesNo(settings.pfChargesInCtc()))
			.field("Leave encashment divisor", was.leaveEncashmentDivisor(), settings.leaveEncashmentDivisor())
			.field("HRA metro cities", was.metroCities(), settings.metroCities())
			.field("PF due day", PaySettings.dueDayLabel(was.pfDueDay()), PaySettings.dueDayLabel(settings.pfDueDay()))
			.field("ESI due day", PaySettings.dueDayLabel(was.esiDueDay()),
					PaySettings.dueDayLabel(settings.esiDueDay()))
			.field("TDS due day", PaySettings.dueDayLabel(was.tdsDueDay()),
					PaySettings.dueDayLabel(settings.tdsDueDay()))
			.field("PT due day", PaySettings.dueDayLabel(was.ptDueDay()), PaySettings.dueDayLabel(settings.ptDueDay()));
		if (changes.isEmpty()) {
			return List.of();
		}
		company.setPaySettings(settings);
		List<String> changed = List.copyOf(changes.after().keySet());
		audit.record(AuditAction.PAY_SETTINGS_CHANGED, Company.AUDIT_TYPE, Company.ID,
				"Changed pay settings: " + String.join(", ", changed), changes);
		return changed;
	}

	/**
	 * Stores a PNG or JPG logo in place of the current one. The old file is deleted once the change commits; the new one
	 * if it rolls back.
	 * @return true when it replaced a logo, false when it added the first one
	 * @throws BusinessRuleException (field {@code file}) for a missing, oversized or wrongly typed file, or an image
	 * that does not decode or has more than 16 megapixels
	 */
	@PreAuthorize("hasRole('ADMIN')")
	@Transactional
	public boolean replaceLogo(MultipartFile file) {
		Company company = company();
		FileStorage.StoredFile stored = storage.store(LOGO_FOLDER, file, LOGO_TYPES);
		String old = company.getLogoPath();
		storage.afterTransaction(old, stored.path());
		company.setLogoPath(stored.path());
		audit.record(AuditAction.COMPANY_LOGO_CHANGED, Company.AUDIT_TYPE, Company.ID,
				((old == null) ? "Added a company logo (" : "Replaced the company logo (") + stored.type().label() + ")");
		return old != null;
	}

	/** @return false when there was no logo */
	@PreAuthorize("hasRole('ADMIN')")
	@Transactional
	public boolean removeLogo() {
		Company company = company();
		String old = company.getLogoPath();
		if (old == null) {
			return false;
		}
		company.setLogoPath(null);
		storage.afterTransaction(old, null);
		audit.record(AuditAction.COMPANY_LOGO_CHANGED, Company.AUDIT_TYPE, Company.ID, "Removed the company logo");
		return true;
	}

	/** Every rule a pay setting must meet, for any caller; the form checks the single-field ones first. */
	static void check(PaySettings settings) {
		if (settings.prorationMethod() == null) {
			throw new BusinessRuleException("prorationMethod", "Choose how to prorate.");
		}
		if (settings.defaultPfPolicy() == null) {
			throw new BusinessRuleException("defaultPfPolicy", "Choose the default PF policy.");
		}
		requireRange("payDay", settings.payDay(), PaySettings.FIRST_PAY_DAY, PaySettings.LAST_PAY_DAY,
				PaySettings.PAY_DAY_RANGE);
		requireRange("attendanceCutoffDay", settings.attendanceCutoffDay(), 1, PaySettings.LAST_CUTOFF_DAY,
				PaySettings.CUTOFF_RANGE);
		requireRange("leaveEncashmentDivisor", settings.leaveEncashmentDivisor(), PaySettings.MIN_DIVISOR,
				PaySettings.MAX_DIVISOR, PaySettings.DIVISOR_RANGE);
		requireRange("pfDueDay", settings.pfDueDay(), 1, PaySettings.LAST_DAY, PaySettings.DUE_DAY_RANGE);
		requireRange("esiDueDay", settings.esiDueDay(), 1, PaySettings.LAST_DAY, PaySettings.DUE_DAY_RANGE);
		requireRange("tdsDueDay", settings.tdsDueDay(), 1, PaySettings.LAST_DAY, PaySettings.DUE_DAY_RANGE);
		requireRange("ptDueDay", settings.ptDueDay(), 1, PaySettings.LAST_DAY, PaySettings.DUE_DAY_RANGE);
		if (settings.attendanceCutoffDay() >= settings.payDay()) {
			throw new BusinessRuleException("attendanceCutoffDay",
					"Close attendance before pay day: choose a day before the " + PaySettings.ordinal(settings.payDay())
							+ ".");
		}
		if (settings.defaultPfPolicy() == PfPolicy.EXCLUDED) {
			throw new BusinessRuleException("defaultPfPolicy",
					"Choose Restrict or Actual. Leaving PF is decided per employee, never for everyone.");
		}
		PaySettings.checkCities(settings.metroCities());
	}

	private static void requireProfile(CompanyProfile profile) {
		if (profile.legalName() == null || profile.addressLine1() == null || profile.city() == null
				|| profile.state() == null || profile.pincode() == null || profile.pan() == null
				|| profile.tan() == null) {
			throw new BusinessRuleException(null, "Enter the legal name, address, PAN and TAN.");
		}
		requireLength("legalName", profile.legalName(), CompanyProfile.MAX_LEGAL_NAME);
		requireLength("brandName", profile.brandName(), CompanyProfile.MAX_BRAND_NAME);
		requireLength("addressLine1", profile.addressLine1(), CompanyProfile.MAX_ADDRESS_LINE);
		requireLength("addressLine2", profile.addressLine2(), CompanyProfile.MAX_ADDRESS_LINE);
		requireLength("city", profile.city(), CompanyProfile.MAX_CITY);
		requireLength("signatoryName", profile.signatoryName(), CompanyProfile.MAX_SIGNATORY_NAME);
		requireLength("signatoryDesignation", profile.signatoryDesignation(), CompanyProfile.MAX_DESIGNATION);
		requireFormat("pincode", profile.pincode(), CompanyProfile.PINCODE_FORMAT, "Enter a 6-digit PIN code.");
		requireFormat("pan", profile.pan(), CompanyProfile.PAN_FORMAT, "Enter a PAN like ABCDE1234F.");
		requireFormat("tan", profile.tan(), CompanyProfile.TAN_FORMAT, "Enter a TAN like PNEP12345C.");
		requireFormat("pfEstablishmentCode", profile.pfEstablishmentCode(), CompanyProfile.PF_CODE_FORMAT,
				"Enter the 15-character code, like MHKOP0012345000.");
		requireFormat("esiEmployerCode", profile.esiEmployerCode(), CompanyProfile.ESI_CODE_FORMAT,
				"Enter the 17-digit employer code.");
		requireFormat("ptRegistrationNumber", profile.ptRegistrationNumber(), CompanyProfile.PTRC_FORMAT,
				"Enter 11 digits followed by P, like 27531234567P.");
		requireDesignation(profile);
	}

	/** A named signatory needs a designation. */
	static void requireDesignation(CompanyProfile profile) {
		if (profile.signatoryName() != null && profile.signatoryDesignation() == null) {
			throw new BusinessRuleException("signatoryDesignation", "Enter the signatory’s designation.");
		}
	}

	private static void requireFormat(String field, String value, String format, String message) {
		if (value != null && !value.matches(format)) {
			throw new BusinessRuleException(field, message);
		}
	}

	private static void requireLength(String field, String value, int max) {
		if (value != null && value.length() > max) {
			throw new BusinessRuleException(field, "Use at most " + max + " characters.");
		}
	}

	private static void requireRange(String field, int value, int min, int max, String message) {
		if (value < min || value > max) {
			throw new BusinessRuleException(field, message);
		}
	}

	private Company company() {
		return companies.findById(Company.ID)
			.orElseThrow(() -> new IllegalStateException("The company row is missing; migration V4 creates it"));
	}

	private static String label(IndianState state) {
		return (state != null) ? state.label() : null;
	}

	private static String yesNo(boolean value) {
		return value ? "Yes" : "No";
	}

}
