package com.kosh.employee;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.kosh.common.BaseEntity;
import com.kosh.common.BusinessRuleException;
import com.kosh.common.EncryptedStringConverter;
import com.kosh.common.Gender;
import com.kosh.common.HmacHasher;
import com.kosh.common.Text;
import com.kosh.company.CompanyProfile;
import com.kosh.org.CostCentre;
import com.kosh.org.Department;
import com.kosh.org.Designation;
import com.kosh.org.Grade;
import com.kosh.org.WorkLocation;
import com.kosh.statutory.PfPolicy;
import com.kosh.user.User;

/**
 * A person who works for the company: personal details, job, statutory numbers and bank account (PLAN T15). The code
 * ({@code KSH-0001}) comes from {@link EmployeeRepository#nextCode()} and never changes.
 * <p>
 * The constraints here (and {@link EmployeeConsistency} between fields) are the one statement of what a valid
 * employee is. Hibernate checks them before every insert and update, so a form, an import or the demo seed can never
 * store an invalid one; a form shows the same messages next to its fields by validating first. Text is tidied by the
 * setters and the address, contact and bank records: trimmed, blank as null, PAN, IFSC and numbers upper case without
 * spaces, e-mail lower case.
 * <p>
 * PAN and the bank account number are encrypted at rest; {@code pan_hash} (HMAC of the PAN) keeps the PAN unique
 * without decrypting anything. Changed only through the {@code com.kosh.employee} package.
 */
@Entity
@Table(name = "employees")
@EmployeeConsistency
public class Employee extends BaseEntity {

	/** Every code is this prefix and a number of at least four digits: KSH-0001, KSH-12345. */
	public static final String CODE_PREFIX = "KSH-";

	/** The entity type in the audit log. */
	public static final String AUDIT_TYPE = "Employee";

	public static final int MAX_NAME = 60;

	/** Youngest age on the joining date: nobody under 14 may be employed in India (PAYROLL-RULES section 15). */
	public static final int MIN_AGE_AT_JOINING = 14;

	/** An age on the joining date that is certainly a typing mistake in the year of birth. */
	public static final int MAX_AGE_AT_JOINING = 100;

	/** Indian mobile number: 10 digits, the first 6 to 9. */
	public static final String MOBILE_FORMAT = "[6-9][0-9]{9}";

	public static final String MOBILE_MESSAGE = "Enter a 10-digit mobile number starting with 6, 7, 8 or 9.";

	/** Universal Account Number of the EPFO: 12 digits. */
	public static final String UAN_FORMAT = "[0-9]{12}";

	/** ESI insurance person (IP) number: 10 digits. */
	public static final String ESI_IP_FORMAT = "[0-9]{10}";

	private static final String NAME_TOO_LONG = "Use at most " + MAX_NAME + " characters.";

	@Column(nullable = false, length = 12, updatable = false)
	private String code;

	@NotBlank(message = "Enter the first name.")
	@Size(max = MAX_NAME, message = NAME_TOO_LONG)
	@Column(nullable = false, length = MAX_NAME)
	private String firstName;

	@Size(max = MAX_NAME, message = NAME_TOO_LONG)
	@Column(length = MAX_NAME)
	private String middleName;

	@Size(max = MAX_NAME, message = NAME_TOO_LONG)
	@Column(length = MAX_NAME)
	private String lastName;

	@NotNull(message = "Choose the gender.")
	@Column(nullable = false, length = 11)
	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	private Gender gender;

	@NotNull(message = "Enter the date of birth.")
	@Column(nullable = false)
	private LocalDate dateOfBirth;

	/** Hibernate's {@code @Email} alone accepts a domain without a dot (ravi@kolhapur), so a dot is required too. */
	@Email(regexp = ".+@.+\\..+", message = "Enter an email address like name@example.com.")
	@Size(max = 254, message = "Use at most 254 characters.")
	@Column(length = 254)
	private String personalEmail;

	@NotBlank(message = "Enter the mobile number.")
	@Pattern(regexp = MOBILE_FORMAT, message = MOBILE_MESSAGE)
	@Column(nullable = false, length = 10)
	private String mobile;

	@Valid
	@Embedded
	@AttributeOverride(name = "line1", column = @Column(name = "current_address_line1", length = 150))
	@AttributeOverride(name = "line2", column = @Column(name = "current_address_line2", length = 150))
	@AttributeOverride(name = "city", column = @Column(name = "current_city", length = 60))
	@AttributeOverride(name = "state", column = @Column(name = "current_state_code", length = 2))
	@AttributeOverride(name = "pincode", column = @Column(name = "current_pincode", length = 6))
	private Address currentAddress;

	@Valid
	@Embedded
	@AttributeOverride(name = "line1", column = @Column(name = "permanent_address_line1", length = 150))
	@AttributeOverride(name = "line2", column = @Column(name = "permanent_address_line2", length = 150))
	@AttributeOverride(name = "city", column = @Column(name = "permanent_city", length = 60))
	@AttributeOverride(name = "state", column = @Column(name = "permanent_state_code", length = 2))
	@AttributeOverride(name = "pincode", column = @Column(name = "permanent_pincode", length = 6))
	private Address permanentAddress;

	@Valid
	@Embedded
	private EmergencyContact emergencyContact;

	@NotNull(message = "Enter the joining date.")
	@Column(nullable = false)
	private LocalDate joiningDate;

	private LocalDate confirmationDate;

	@NotNull(message = "Choose the employment type.")
	@Column(nullable = false, length = 10)
	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	private EmploymentType employmentType;

	private LocalDate contractEndDate;

	@NotNull(message = "Choose the status.")
	@Column(nullable = false, length = 10)
	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	private EmployeeStatus status = EmployeeStatus.ACTIVE;

	@NotNull(message = "Choose the department.")
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "department_id")
	private Department department;

	@NotNull(message = "Choose the designation.")
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "designation_id")
	private Designation designation;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "grade_id")
	private Grade grade;

	@NotNull(message = "Choose the work location.")
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "location_id")
	private WorkLocation location;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "cost_centre_id")
	private CostCentre costCentre;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "reporting_manager_id")
	private Employee reportingManager;

	@OneToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "user_id")
	private User user;

	@Pattern(regexp = CompanyProfile.PAN_FORMAT, message = "Enter a PAN like ABCDE1234F: five letters, four digits, a letter.")
	@Convert(converter = EncryptedStringConverter.class)
	@Column(length = 255)
	private String pan;

	@Column(length = 64)
	private String panHash;

	@Pattern(regexp = UAN_FORMAT, message = "Enter the 12-digit UAN.")
	@Column(length = 12)
	private String uan;

	@Pattern(regexp = ESI_IP_FORMAT, message = "Enter the 10-digit ESI IP number.")
	@Column(length = 10)
	private String esiIpNumber;

	@NotNull(message = "Choose the PF policy.")
	@Column(nullable = false, length = 10)
	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	private PfPolicy pfPolicy;

	@Column(nullable = false)
	private boolean ptExempt;

	@Size(max = 200, message = "Use at most 200 characters.")
	@Column(length = 200)
	private String ptExemptReason;

	@Column(nullable = false)
	private boolean personWithDisability;

	@Valid
	@Embedded
	private BankAccount bankAccount;

	protected Employee() {
	}

	/** A new employee with a code from {@link EmployeeRepository#nextCode()}; set the rest before saving. */
	public Employee(String code) {
		this.code = Objects.requireNonNull(code, "code");
	}

	/** The code for the n-th employee: {@code KSH-0001}; from 10,000 on the number simply grows. */
	public static String code(int number) {
		return CODE_PREFIX + "%04d".formatted(number);
	}

	/** First, middle and last name, single-spaced. */
	public String fullName() {
		return Stream.of(firstName, middleName, lastName).filter(Objects::nonNull).collect(Collectors.joining(" "));
	}

	// ------------------------------------------------------------------ getters

	public String getCode() {
		return code;
	}

	public String getFirstName() {
		return firstName;
	}

	public String getMiddleName() {
		return middleName;
	}

	public String getLastName() {
		return lastName;
	}

	public Gender getGender() {
		return gender;
	}

	public LocalDate getDateOfBirth() {
		return dateOfBirth;
	}

	public String getPersonalEmail() {
		return personalEmail;
	}

	public String getMobile() {
		return mobile;
	}

	public Address getCurrentAddress() {
		return currentAddress;
	}

	public Address getPermanentAddress() {
		return permanentAddress;
	}

	public EmergencyContact getEmergencyContact() {
		return emergencyContact;
	}

	public LocalDate getJoiningDate() {
		return joiningDate;
	}

	public LocalDate getConfirmationDate() {
		return confirmationDate;
	}

	public EmploymentType getEmploymentType() {
		return employmentType;
	}

	public LocalDate getContractEndDate() {
		return contractEndDate;
	}

	public EmployeeStatus getStatus() {
		return status;
	}

	public Department getDepartment() {
		return department;
	}

	public Designation getDesignation() {
		return designation;
	}

	public Grade getGrade() {
		return grade;
	}

	public WorkLocation getLocation() {
		return location;
	}

	public CostCentre getCostCentre() {
		return costCentre;
	}

	public Employee getReportingManager() {
		return reportingManager;
	}

	/** The sign-in account, or null when this employee has none. */
	public User getUser() {
		return user;
	}

	/** The PAN in full: show it only to HR, on the payslip and to the employee; mask it everywhere else. */
	public String getPan() {
		return pan;
	}

	public String getUan() {
		return uan;
	}

	public String getEsiIpNumber() {
		return esiIpNumber;
	}

	public PfPolicy getPfPolicy() {
		return pfPolicy;
	}

	public boolean isPtExempt() {
		return ptExempt;
	}

	public String getPtExemptReason() {
		return ptExemptReason;
	}

	public boolean isPersonWithDisability() {
		return personWithDisability;
	}

	/** Null when no bank details are recorded yet. */
	public BankAccount getBankAccount() {
		return bankAccount;
	}

	// ------------------------------------------------------------------ setters (this package only)

	void setFirstName(String firstName) {
		this.firstName = Text.clean(firstName);
	}

	void setMiddleName(String middleName) {
		this.middleName = Text.clean(middleName);
	}

	void setLastName(String lastName) {
		this.lastName = Text.clean(lastName);
	}

	void setGender(Gender gender) {
		this.gender = gender;
	}

	void setDateOfBirth(LocalDate dateOfBirth) {
		this.dateOfBirth = dateOfBirth;
	}

	void setPersonalEmail(String personalEmail) {
		String email = Text.clean(personalEmail);
		this.personalEmail = (email == null) ? null : email.toLowerCase(Locale.ROOT);
	}

	void setMobile(String mobile) {
		this.mobile = Text.code(mobile);
	}

	void setCurrentAddress(Address currentAddress) {
		this.currentAddress = currentAddress;
	}

	void setPermanentAddress(Address permanentAddress) {
		this.permanentAddress = permanentAddress;
	}

	void setEmergencyContact(EmergencyContact emergencyContact) {
		this.emergencyContact = emergencyContact;
	}

	void setJoiningDate(LocalDate joiningDate) {
		this.joiningDate = joiningDate;
	}

	void setConfirmationDate(LocalDate confirmationDate) {
		this.confirmationDate = confirmationDate;
	}

	/** The type and, for fixed-term staff and interns, when the contract ends (null for none). */
	void setEmploymentType(EmploymentType employmentType, LocalDate contractEndDate) {
		this.employmentType = employmentType;
		this.contractEndDate = contractEndDate;
	}

	void setStatus(EmployeeStatus status) {
		this.status = status;
	}

	void setDepartment(Department department) {
		this.department = department;
	}

	void setDesignation(Designation designation) {
		this.designation = designation;
	}

	void setGrade(Grade grade) {
		this.grade = grade;
	}

	void setLocation(WorkLocation location) {
		this.location = location;
	}

	void setCostCentre(CostCentre costCentre) {
		this.costCentre = costCentre;
	}

	/**
	 * Sets whom this employee reports to (null for nobody).
	 * @throws BusinessRuleException on field {@code reportingManager} when that is someone who has left, the employee
	 * themselves or someone who already reports to them, directly or further down, since a reporting line must never loop
	 */
	void setReportingManager(Employee manager) {
		checkReportingLine(this, getId(), manager);
		this.reportingManager = manager;
	}

	/**
	 * The rules of {@link #setReportingManager} without setting anything: {@code self} is the employee (or an unsaved
	 * copy holding a form's changes, then {@code selfId} is the stored employee's id; null for someone new).
	 * @throws BusinessRuleException on field {@code reportingManager} when the line would start at someone who has
	 * left or would loop
	 */
	static void checkReportingLine(Employee self, Long selfId, Employee manager) {
		if (manager != null && manager.getStatus() == EmployeeStatus.EXITED) {
			throw new BusinessRuleException("reportingManager",
					"Choose someone else: %s has left the company.".formatted(manager.fullName()));
		}
		Set<Long> seen = new HashSet<>();
		for (Employee above = manager; above != null; above = above.getReportingManager()) {
			if (above == self || (selfId != null && selfId.equals(above.getId()))) {
				throw new BusinessRuleException("reportingManager", (above == manager)
						? "Choose someone else: an employee can’t report to themselves."
						: "Choose someone else: %s already reports to %s.".formatted(manager.fullName(), self.fullName()));
			}
			if (above.getId() != null && !seen.add(above.getId())) {
				break;
			}
		}
	}

	void setUser(User user) {
		this.user = user;
	}

	/** Stores the PAN (upper case, no spaces; null to remove it) and its look-up hash, which keeps it unique. */
	void setPan(String pan, HmacHasher hasher) {
		this.pan = Text.code(pan);
		this.panHash = hasher.hash(this.pan);
	}

	void setUan(String uan) {
		this.uan = Text.code(uan);
	}

	void setEsiIpNumber(String esiIpNumber) {
		this.esiIpNumber = Text.code(esiIpNumber);
	}

	void setPfPolicy(PfPolicy pfPolicy) {
		this.pfPolicy = pfPolicy;
	}

	/** Exempt from professional tax, with the reason (required then, dropped otherwise). */
	void setPtExemption(boolean exempt, String reason) {
		this.ptExempt = exempt;
		this.ptExemptReason = exempt ? Text.clean(reason) : null;
	}

	void setPersonWithDisability(boolean personWithDisability) {
		this.personWithDisability = personWithDisability;
	}

	void setBankAccount(BankAccount bankAccount) {
		this.bankAccount = bankAccount;
	}

}
