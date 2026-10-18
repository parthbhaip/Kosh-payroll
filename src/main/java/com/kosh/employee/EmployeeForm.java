package com.kosh.employee;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.Locale;

import org.springframework.format.annotation.DateTimeFormat;

import com.kosh.common.Gender;
import com.kosh.common.IndianState;
import com.kosh.common.Text;
import com.kosh.statutory.PfPolicy;

/**
 * The add or edit employee form, kept in the HTTP session as a draft while HR moves through the steps (PLAN T17).
 * Fields hold what was typed; {@link EmployeeService} checks them against the {@link Employee} rules and saves them.
 * Each page binds only the fields of its own {@link EmployeeStep}, so the record fields at the bottom (id, version,
 * what is on file) are never taken from a request.
 * <p>
 * For an edit, a PAN the viewer may not see in full and a stored bank account number are not put in the form: their
 * masks are, and leaving the field empty keeps the stored value.
 */
public class EmployeeForm implements Serializable {

	@Serial
	private static final long serialVersionUID = 1L;

	// Personal
	private String firstName;

	private String middleName;

	private String lastName;

	private Gender gender;

	@DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
	private LocalDate dateOfBirth;

	private String personalEmail;

	private String mobile;

	private String currentLine1;

	private String currentLine2;

	private String currentCity;

	private IndianState currentState;

	private String currentPincode;

	private boolean permanentSame;

	private String permanentLine1;

	private String permanentLine2;

	private String permanentCity;

	private IndianState permanentState;

	private String permanentPincode;

	private String emergencyName;

	private String emergencyRelation;

	private String emergencyPhone;

	// Job
	private Long departmentId;

	private Long designationId;

	private Long gradeId;

	private Long locationId;

	private Long costCentreId;

	private Long reportingManagerId;

	@DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
	private LocalDate joiningDate;

	private EmploymentType employmentType;

	@DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
	private LocalDate contractEndDate;

	@DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
	private LocalDate confirmationDate;

	// Statutory
	private String pan;

	private String uan;

	private String esiIpNumber;

	private PfPolicy pfPolicy;

	private boolean ptExempt;

	private String ptExemptReason;

	private boolean personWithDisability;

	// Bank
	private String holderName;

	private String bankName;

	private String ifsc;

	private String accountNumber;

	private String accountNumberConfirm;

	// Review
	private boolean createLogin;

	private String loginEmail;

	// The record (never bound from a request)
	private Long id;

	private Long version;

	private String code;

	private String recordName;

	private String panMasked;

	private String accountMasked;

	private String loginOnFile;

	private Long managerOnFile;

	private EmployeeStep reached = EmployeeStep.PERSONAL;

	/** Whether this form changes an existing employee rather than adding one. */
	public boolean isEditing() {
		return id != null;
	}

	/** Whether the stored PAN stays: the viewer saw only its mask and typed nothing in its place. */
	public boolean keepsPan() {
		return panMasked != null && Text.clean(pan) == null;
	}

	/** Whether the stored account number stays: there is one and nothing was typed in its place. */
	public boolean keepsAccount() {
		return accountMasked != null && Text.clean(accountNumber) == null;
	}

	/** Whether every bank field is empty, which records no bank details (or removes them). */
	public boolean bankEmpty() {
		return Text.clean(holderName) == null && Text.clean(bankName) == null && Text.clean(ifsc) == null
				&& Text.clean(accountNumber) == null;
	}

	/** First, middle and last name as typed, single-spaced; null when none is typed yet. */
	public String fullName() {
		return join(" ", firstName, middleName, lastName);
	}

	/** The current address on one line, or null when none is given. */
	public String currentAddressText() {
		return address(currentLine1, currentLine2, currentCity, currentState, currentPincode);
	}

	/** The permanent address on one line, or null when none is given (or it is the current one). */
	public String permanentAddressText() {
		return address(permanentLine1, permanentLine2, permanentCity, permanentState, permanentPincode);
	}

	/** {@code Anil Deshmukh (Brother), 9890011223}, or null when no contact is given. */
	public String emergencyText() {
		String name = Text.clean(emergencyName);
		String relation = Text.clean(emergencyRelation);
		return join(", ", (name != null && relation != null) ? name + " (" + relation + ")" : join(" ", name, relation),
				Text.clean(emergencyPhone));
	}

	private static String address(String line1, String line2, String city, IndianState state, String pincode) {
		String place = join(" ", (state != null) ? state.label() : null, Text.clean(pincode));
		return join(", ", Text.clean(line1), Text.clean(line2), Text.clean(city), place);
	}

	private static String join(String separator, String... parts) {
		StringBuilder text = new StringBuilder();
		for (String part : parts) {
			String clean = Text.clean(part);
			if (clean != null) {
				text.append(text.isEmpty() ? "" : separator).append(clean);
			}
		}
		return text.isEmpty() ? null : text.toString();
	}

	/** Whether the step may be opened: it is one already reached. */
	public boolean canOpen(EmployeeStep step) {
		return step.ordinal() <= reached.ordinal();
	}

	/** Opens the steps up to this one. */
	void reach(EmployeeStep step) {
		if (step.ordinal() > reached.ordinal()) {
			reached = step;
		}
	}

	/** Tidies typed text the way the employee record stores it, so the pages show it as it will be saved. */
	void tidy() {
		firstName = Text.clean(firstName);
		middleName = Text.clean(middleName);
		lastName = Text.clean(lastName);
		personalEmail = (Text.clean(personalEmail) == null) ? null : Text.clean(personalEmail).toLowerCase(Locale.ROOT);
		mobile = Text.code(mobile);
		currentLine1 = Text.clean(currentLine1);
		currentLine2 = Text.clean(currentLine2);
		currentCity = Text.clean(currentCity);
		currentPincode = Text.code(currentPincode);
		permanentLine1 = Text.clean(permanentLine1);
		permanentLine2 = Text.clean(permanentLine2);
		permanentCity = Text.clean(permanentCity);
		permanentPincode = Text.code(permanentPincode);
		emergencyName = Text.clean(emergencyName);
		emergencyRelation = Text.clean(emergencyRelation);
		emergencyPhone = Text.code(emergencyPhone);
		pan = Text.code(pan);
		uan = Text.code(uan);
		esiIpNumber = Text.code(esiIpNumber);
		ptExemptReason = Text.clean(ptExemptReason);
		holderName = Text.clean(holderName);
		bankName = Text.clean(bankName);
		ifsc = Text.code(ifsc);
		accountNumber = Text.code(accountNumber);
		accountNumberConfirm = Text.code(accountNumberConfirm);
		loginEmail = Text.clean(loginEmail);
	}

	// ------------------------------------------------------------------ the record

	public Long getId() {
		return id;
	}

	void setId(Long id) {
		this.id = id;
	}

	public Long getVersion() {
		return version;
	}

	void setVersion(Long version) {
		this.version = version;
	}

	/** The employee code, for an edit. */
	public String getCode() {
		return code;
	}

	void setCode(String code) {
		this.code = code;
	}

	/** The name on the record when the edit started (page title and links). */
	public String getRecordName() {
		return recordName;
	}

	void setRecordName(String recordName) {
		this.recordName = recordName;
	}

	/** The stored PAN's mask when the viewer may not see it in full, else null. */
	public String getPanMasked() {
		return panMasked;
	}

	void setPanMasked(String panMasked) {
		this.panMasked = panMasked;
	}

	/** The stored account number's mask, else null. */
	public String getAccountMasked() {
		return accountMasked;
	}

	void setAccountMasked(String accountMasked) {
		this.accountMasked = accountMasked;
	}

	/** The e-mail of the employee's sign-in account, or null when they have none. */
	public String getLoginOnFile() {
		return loginOnFile;
	}

	void setLoginOnFile(String loginOnFile) {
		this.loginOnFile = loginOnFile;
	}

	/** Whom the employee reported to when the edit started (may be someone who has left since). */
	public Long getManagerOnFile() {
		return managerOnFile;
	}

	void setManagerOnFile(Long managerOnFile) {
		this.managerOnFile = managerOnFile;
	}

	public EmployeeStep getReached() {
		return reached;
	}

	// ------------------------------------------------------------------ bound fields

	public String getFirstName() {
		return firstName;
	}

	public void setFirstName(String firstName) {
		this.firstName = firstName;
	}

	public String getMiddleName() {
		return middleName;
	}

	public void setMiddleName(String middleName) {
		this.middleName = middleName;
	}

	public String getLastName() {
		return lastName;
	}

	public void setLastName(String lastName) {
		this.lastName = lastName;
	}

	public Gender getGender() {
		return gender;
	}

	public void setGender(Gender gender) {
		this.gender = gender;
	}

	public LocalDate getDateOfBirth() {
		return dateOfBirth;
	}

	public void setDateOfBirth(LocalDate dateOfBirth) {
		this.dateOfBirth = dateOfBirth;
	}

	public String getPersonalEmail() {
		return personalEmail;
	}

	public void setPersonalEmail(String personalEmail) {
		this.personalEmail = personalEmail;
	}

	public String getMobile() {
		return mobile;
	}

	public void setMobile(String mobile) {
		this.mobile = mobile;
	}

	public String getCurrentLine1() {
		return currentLine1;
	}

	public void setCurrentLine1(String currentLine1) {
		this.currentLine1 = currentLine1;
	}

	public String getCurrentLine2() {
		return currentLine2;
	}

	public void setCurrentLine2(String currentLine2) {
		this.currentLine2 = currentLine2;
	}

	public String getCurrentCity() {
		return currentCity;
	}

	public void setCurrentCity(String currentCity) {
		this.currentCity = currentCity;
	}

	public IndianState getCurrentState() {
		return currentState;
	}

	public void setCurrentState(IndianState currentState) {
		this.currentState = currentState;
	}

	public String getCurrentPincode() {
		return currentPincode;
	}

	public void setCurrentPincode(String currentPincode) {
		this.currentPincode = currentPincode;
	}

	public boolean isPermanentSame() {
		return permanentSame;
	}

	public void setPermanentSame(boolean permanentSame) {
		this.permanentSame = permanentSame;
	}

	public String getPermanentLine1() {
		return permanentLine1;
	}

	public void setPermanentLine1(String permanentLine1) {
		this.permanentLine1 = permanentLine1;
	}

	public String getPermanentLine2() {
		return permanentLine2;
	}

	public void setPermanentLine2(String permanentLine2) {
		this.permanentLine2 = permanentLine2;
	}

	public String getPermanentCity() {
		return permanentCity;
	}

	public void setPermanentCity(String permanentCity) {
		this.permanentCity = permanentCity;
	}

	public IndianState getPermanentState() {
		return permanentState;
	}

	public void setPermanentState(IndianState permanentState) {
		this.permanentState = permanentState;
	}

	public String getPermanentPincode() {
		return permanentPincode;
	}

	public void setPermanentPincode(String permanentPincode) {
		this.permanentPincode = permanentPincode;
	}

	public String getEmergencyName() {
		return emergencyName;
	}

	public void setEmergencyName(String emergencyName) {
		this.emergencyName = emergencyName;
	}

	public String getEmergencyRelation() {
		return emergencyRelation;
	}

	public void setEmergencyRelation(String emergencyRelation) {
		this.emergencyRelation = emergencyRelation;
	}

	public String getEmergencyPhone() {
		return emergencyPhone;
	}

	public void setEmergencyPhone(String emergencyPhone) {
		this.emergencyPhone = emergencyPhone;
	}

	public Long getDepartmentId() {
		return departmentId;
	}

	public void setDepartmentId(Long departmentId) {
		this.departmentId = departmentId;
	}

	public Long getDesignationId() {
		return designationId;
	}

	public void setDesignationId(Long designationId) {
		this.designationId = designationId;
	}

	public Long getGradeId() {
		return gradeId;
	}

	public void setGradeId(Long gradeId) {
		this.gradeId = gradeId;
	}

	public Long getLocationId() {
		return locationId;
	}

	public void setLocationId(Long locationId) {
		this.locationId = locationId;
	}

	public Long getCostCentreId() {
		return costCentreId;
	}

	public void setCostCentreId(Long costCentreId) {
		this.costCentreId = costCentreId;
	}

	public Long getReportingManagerId() {
		return reportingManagerId;
	}

	public void setReportingManagerId(Long reportingManagerId) {
		this.reportingManagerId = reportingManagerId;
	}

	public LocalDate getJoiningDate() {
		return joiningDate;
	}

	public void setJoiningDate(LocalDate joiningDate) {
		this.joiningDate = joiningDate;
	}

	public EmploymentType getEmploymentType() {
		return employmentType;
	}

	public void setEmploymentType(EmploymentType employmentType) {
		this.employmentType = employmentType;
	}

	public LocalDate getContractEndDate() {
		return contractEndDate;
	}

	public void setContractEndDate(LocalDate contractEndDate) {
		this.contractEndDate = contractEndDate;
	}

	public LocalDate getConfirmationDate() {
		return confirmationDate;
	}

	public void setConfirmationDate(LocalDate confirmationDate) {
		this.confirmationDate = confirmationDate;
	}

	public String getPan() {
		return pan;
	}

	public void setPan(String pan) {
		this.pan = pan;
	}

	public String getUan() {
		return uan;
	}

	public void setUan(String uan) {
		this.uan = uan;
	}

	public String getEsiIpNumber() {
		return esiIpNumber;
	}

	public void setEsiIpNumber(String esiIpNumber) {
		this.esiIpNumber = esiIpNumber;
	}

	public PfPolicy getPfPolicy() {
		return pfPolicy;
	}

	public void setPfPolicy(PfPolicy pfPolicy) {
		this.pfPolicy = pfPolicy;
	}

	public boolean isPtExempt() {
		return ptExempt;
	}

	public void setPtExempt(boolean ptExempt) {
		this.ptExempt = ptExempt;
	}

	public String getPtExemptReason() {
		return ptExemptReason;
	}

	public void setPtExemptReason(String ptExemptReason) {
		this.ptExemptReason = ptExemptReason;
	}

	public boolean isPersonWithDisability() {
		return personWithDisability;
	}

	public void setPersonWithDisability(boolean personWithDisability) {
		this.personWithDisability = personWithDisability;
	}

	public String getHolderName() {
		return holderName;
	}

	public void setHolderName(String holderName) {
		this.holderName = holderName;
	}

	public String getBankName() {
		return bankName;
	}

	public void setBankName(String bankName) {
		this.bankName = bankName;
	}

	public String getIfsc() {
		return ifsc;
	}

	public void setIfsc(String ifsc) {
		this.ifsc = ifsc;
	}

	public String getAccountNumber() {
		return accountNumber;
	}

	public void setAccountNumber(String accountNumber) {
		this.accountNumber = accountNumber;
	}

	public String getAccountNumberConfirm() {
		return accountNumberConfirm;
	}

	public void setAccountNumberConfirm(String accountNumberConfirm) {
		this.accountNumberConfirm = accountNumberConfirm;
	}

	public boolean isCreateLogin() {
		return createLogin;
	}

	public void setCreateLogin(boolean createLogin) {
		this.createLogin = createLogin;
	}

	public String getLoginEmail() {
		return loginEmail;
	}

	public void setLoginEmail(String loginEmail) {
		this.loginEmail = loginEmail;
	}

}
