package com.kosh.employee;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

import jakarta.persistence.EntityManager;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kosh.audit.AuditAction;
import com.kosh.audit.AuditChanges;
import com.kosh.audit.AuditService;
import com.kosh.common.BusinessRuleException;
import com.kosh.common.Fmt;
import com.kosh.common.HmacHasher;
import com.kosh.common.IndianState;
import com.kosh.common.Masking;
import com.kosh.common.NotFoundException;
import com.kosh.common.Text;
import com.kosh.company.CompanyService;
import com.kosh.org.CostCentre;
import com.kosh.org.Department;
import com.kosh.org.Designation;
import com.kosh.org.Grade;
import com.kosh.org.OrgItem;
import com.kosh.org.OrgKind;
import com.kosh.org.OrgService;
import com.kosh.org.WorkLocation;
import com.kosh.user.Role;
import com.kosh.user.User;
import com.kosh.user.UserAdminService;
import com.kosh.user.UserRepository;

/**
 * Adding and changing employees for HR and ADMIN (PLAN T17). The form travels as an {@link EmployeeForm} draft; this
 * service checks it a step at a time against the {@link Employee} rules (the one statement of them) plus what only the
 * database knows: duplicates of the personal e-mail, PAN, UAN and ESI IP number, organisation items that are still
 * active, a reporting line that does not loop and a free sign-in e-mail. Adding gives the employee the next code and,
 * when asked, a sign-in account with the Employee role and an invite. Every add and change goes in the audit log, PAN
 * and account numbers masked; bank changes get their own event.
 */
@Service
@PreAuthorize("hasAnyRole('HR', 'ADMIN')")
public class EmployeeService {

	/** A rule the draft breaks, on a form field (null for the form as a whole). */
	public record Problem(String field, String message) {

		/** The step that shows the field, or null. */
		public EmployeeStep step() {
			return EmployeeStep.of(field);
		}

	}

	/**
	 * The choices on the Job step, id to label, by name: organisation items that are active (and the employee's current
	 * ones, marked inactive); for managers everyone who has not left except the employee themselves, plus the current
	 * manager even after they have left.
	 */
	public record Options(Map<Long, String> departments, Map<Long, String> designations, Map<Long, String> grades,
			Map<Long, String> locations, Map<Long, String> costCentres, Map<Long, String> managers) {

		/** Whether the organisation has what every employee needs: a department, a designation and a work location. */
		public boolean ready() {
			return !departments.isEmpty() && !designations.isEmpty() && !locations.isEmpty();
		}

	}

	/** The added employee; {@code invitedEmail} is where the sign-in invite went, null when none was asked for. */
	public record Created(long id, String code, String name, String invitedEmail) {

	}

	/** A saved edit: the name, the labels of what changed (empty for nothing) and where an invite went, if one did. */
	public record Saved(String name, List<String> changed, String invitedEmail) {

	}

	static final String RACE = "Another employee with this PAN, UAN or ESI IP number was saved a moment ago. Check the statutory details and try again.";

	/** The code of the unsaved copy a draft is checked on; never stored. */
	private static final String DRAFT_CODE = "KSH-DRAFT";

	private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

	private static final String PAN = "PAN";

	private static final String ACCOUNT = "Account number";

	/** The audit labels of the salary account, which changes get their own event. */
	private static final Set<String> BANK_FIELDS = Set.of("Account holder", "Bank", "IFSC", ACCOUNT);

	/** Where a rule on the employee shows on the form, for the paths that are not a form field's own name. */
	private static final Map<String, String> FIELDS = Map.ofEntries(Map.entry("currentAddress.line1", "currentLine1"),
			Map.entry("currentAddress.line2", "currentLine2"), Map.entry("currentAddress.city", "currentCity"),
			Map.entry("currentAddress.state", "currentState"), Map.entry("currentAddress.pincode", "currentPincode"),
			Map.entry("permanentAddress.line1", "permanentLine1"), Map.entry("permanentAddress.line2", "permanentLine2"),
			Map.entry("permanentAddress.city", "permanentCity"), Map.entry("permanentAddress.state", "permanentState"),
			Map.entry("permanentAddress.pincode", "permanentPincode"), Map.entry("emergencyContact.name", "emergencyName"),
			Map.entry("emergencyContact.relation", "emergencyRelation"),
			Map.entry("emergencyContact.phone", "emergencyPhone"), Map.entry("department", "departmentId"),
			Map.entry("designation", "designationId"), Map.entry("grade", "gradeId"), Map.entry("location", "locationId"),
			Map.entry("costCentre", "costCentreId"), Map.entry("reportingManager", "reportingManagerId"),
			Map.entry("bankAccount.bankName", "bankName"), Map.entry("bankAccount.ifsc", "ifsc"),
			Map.entry("bankAccount.accountNumber", "accountNumber"), Map.entry("bankAccount.holderName", "holderName"));

	/** The stored employee (null while adding) and the choices, loaded once per check or save. */
	private record Context(Employee stored, Options options) {

	}

	private final EmployeeRepository employees;

	private final OrgService org;

	private final UserAdminService accounts;

	private final UserRepository users;

	private final CompanyService company;

	private final AuditService audit;

	private final HmacHasher hasher;

	private final Validator validator;

	private final EntityManager em;

	private final Fmt fmt;

	public EmployeeService(EmployeeRepository employees, OrgService org, UserAdminService accounts, UserRepository users,
			CompanyService company, AuditService audit, HmacHasher hasher, Validator validator, EntityManager em,
			Fmt fmt) {
		this.employees = employees;
		this.org = org;
		this.accounts = accounts;
		this.users = users;
		this.company = company;
		this.audit = audit;
		this.hasher = hasher;
		this.validator = validator;
		this.em = em;
		this.fmt = fmt;
	}

	/** An empty form for a new employee: the company's default PF policy, one address and a sign-in account asked for. */
	@Transactional(readOnly = true)
	public EmployeeForm newDraft() {
		EmployeeForm form = new EmployeeForm();
		form.setPfPolicy(company.paySettings().defaultPfPolicy());
		form.setPermanentSame(true);
		form.setCreateLogin(true);
		return form;
	}

	/**
	 * A form holding the employee as stored, every step open. The PAN is filled in only for a viewer who may see it in
	 * full (HR); others get its mask, and so does the account number for everyone.
	 * @throws NotFoundException when there is no such employee
	 */
	@Transactional(readOnly = true)
	public EmployeeForm draftFor(long id, Authentication viewer) {
		Employee employee = find(id);
		EmployeeForm form = new EmployeeForm();
		form.setId(employee.getId());
		form.setVersion(employee.getVersion());
		form.setCode(employee.getCode());
		form.setRecordName(employee.fullName());
		form.setFirstName(employee.getFirstName());
		form.setMiddleName(employee.getMiddleName());
		form.setLastName(employee.getLastName());
		form.setGender(employee.getGender());
		form.setDateOfBirth(employee.getDateOfBirth());
		form.setPersonalEmail(employee.getPersonalEmail());
		form.setMobile(employee.getMobile());
		Address current = employee.getCurrentAddress();
		if (current != null) {
			form.setCurrentLine1(current.line1());
			form.setCurrentLine2(current.line2());
			form.setCurrentCity(current.city());
			form.setCurrentState(current.state());
			form.setCurrentPincode(current.pincode());
		}
		Address permanent = employee.getPermanentAddress();
		form.setPermanentSame(Objects.equals(current, permanent));
		if (permanent != null && !form.isPermanentSame()) {
			form.setPermanentLine1(permanent.line1());
			form.setPermanentLine2(permanent.line2());
			form.setPermanentCity(permanent.city());
			form.setPermanentState(permanent.state());
			form.setPermanentPincode(permanent.pincode());
		}
		EmergencyContact contact = employee.getEmergencyContact();
		if (contact != null) {
			form.setEmergencyName(contact.name());
			form.setEmergencyRelation(contact.relation());
			form.setEmergencyPhone(contact.phone());
		}
		form.setDepartmentId(id(employee.getDepartment()));
		form.setDesignationId(id(employee.getDesignation()));
		form.setGradeId(id(employee.getGrade()));
		form.setLocationId(id(employee.getLocation()));
		form.setCostCentreId(id(employee.getCostCentre()));
		Long manager = (employee.getReportingManager() == null) ? null : employee.getReportingManager().getId();
		form.setReportingManagerId(manager);
		form.setManagerOnFile(manager);
		form.setJoiningDate(employee.getJoiningDate());
		form.setEmploymentType(employee.getEmploymentType());
		form.setContractEndDate(employee.getContractEndDate());
		form.setConfirmationDate(employee.getConfirmationDate());
		if (seesFullPan(viewer)) {
			form.setPan(employee.getPan());
		}
		else {
			form.setPanMasked(Masking.pan(employee.getPan()));
		}
		form.setUan(employee.getUan());
		form.setEsiIpNumber(employee.getEsiIpNumber());
		form.setPfPolicy(employee.getPfPolicy());
		form.setPtExempt(employee.isPtExempt());
		form.setPtExemptReason(employee.getPtExemptReason());
		form.setPersonWithDisability(employee.isPersonWithDisability());
		BankAccount bank = employee.getBankAccount();
		if (bank != null) {
			form.setHolderName(bank.holderName());
			form.setBankName(bank.bankName());
			form.setIfsc(bank.ifsc());
			form.setAccountMasked(Masking.account(bank.accountNumber()));
		}
		form.setLoginOnFile((employee.getUser() == null) ? null : employee.getUser().getEmail());
		form.reach(EmployeeStep.REVIEW);
		return form;
	}

	/** The PAN in full only for HR (CLAUDE.md > Sensitive data); ADMIN and everyone else see its mask. */
	public static boolean seesFullPan(Authentication viewer) {
		return Role.fromAuthorities(viewer.getAuthorities()).contains(Role.HR);
	}

	@Transactional(readOnly = true)
	public Options options(EmployeeForm form) {
		return options(form, stored(form));
	}

	/**
	 * Every rule the draft breaks on one step (all steps for null), in page order, at most one per field. On the Job
	 * step, an age that does not fit the joining date is reported on the joining date, the field just typed.
	 */
	@Transactional(readOnly = true)
	public List<Problem> problems(EmployeeForm form, EmployeeStep step) {
		Employee stored = stored(form);
		List<Problem> all = check(form, new Context(stored, options(form, stored)));
		if (step == null) {
			return all;
		}
		Map<String, String> onStep = new LinkedHashMap<>();
		for (Problem problem : all) {
			String field = (step == EmployeeStep.JOB && problem.field().equals("dateOfBirth")) ? "joiningDate"
					: problem.field();
			if (EmployeeStep.of(field) == step) {
				onStep.putIfAbsent(field, problem.message());
			}
		}
		return sorted(onStep);
	}

	/**
	 * Adds the employee with the next code and, when the form asks for it, a sign-in account with the Employee role
	 * and an e-mailed invite. A failed e-mail rolls everything back.
	 * @throws BusinessRuleException for the first rule the form breaks, or when a duplicate was saved a moment ago
	 */
	@Transactional
	public Created create(EmployeeForm form, Authentication actor) {
		if (form.isEditing()) {
			throw new IllegalArgumentException("The form edits employee " + form.getId());
		}
		Context context = new Context(null, options(form, null));
		requireValid(form, context);
		Employee employee = new Employee(employees.nextCode());
		apply(form, employee, context);
		if (form.getReportingManagerId() != null) {
			employee.setReportingManager(manager(form.getReportingManagerId()));
		}
		save(employee);
		String invited = form.isCreateLogin() ? invite(employee, form, actor) : null;
		AuditChanges changes = AuditChanges.create();
		values(employee).forEach((label, value) -> compare(changes, label, null, value));
		audit.record(AuditAction.EMPLOYEE_ADDED, Employee.AUDIT_TYPE, employee.getId(),
				"Added %s (%s)".formatted(employee.fullName(), employee.getCode()), changes);
		return new Created(employee.getId(), employee.getCode(), employee.fullName(), invited);
	}

	/**
	 * Saves an edit. Bank changes are recorded as their own event, the rest as one "details changed" event; nothing is
	 * recorded when nothing changed.
	 * @throws OptimisticLockingFailureException when the record changed after the form was filled from it
	 * @throws BusinessRuleException for the first rule the form breaks
	 */
	@Transactional
	public Saved update(long id, EmployeeForm form, Authentication actor) {
		Employee employee = find(id);
		if (!Objects.equals(form.getId(), employee.getId())) {
			throw new IllegalArgumentException("The form is for employee " + form.getId());
		}
		if (!Objects.equals(form.getVersion(), employee.getVersion())) {
			throw new OptimisticLockingFailureException("Employee " + id + " changed after the form was filled");
		}
		Context context = new Context(employee, options(form, employee));
		requireValid(form, context);
		Map<String, String> before = values(employee);
		apply(form, employee, context);
		if (!Objects.equals(form.getReportingManagerId(), form.getManagerOnFile())) {
			employee.setReportingManager(
					(form.getReportingManagerId() == null) ? null : manager(form.getReportingManagerId()));
		}
		save(employee);
		String invited = (form.isCreateLogin() && employee.getUser() == null) ? invite(employee, form, actor) : null;
		AuditChanges details = AuditChanges.create();
		AuditChanges bank = AuditChanges.create();
		values(employee).forEach((label, value) -> compare(BANK_FIELDS.contains(label) ? bank : details, label,
				before.get(label), value));
		String who = "%s (%s)".formatted(employee.fullName(), employee.getCode());
		if (!details.isEmpty()) {
			audit.record(AuditAction.EMPLOYEE_UPDATED, Employee.AUDIT_TYPE, id,
					"Changed %s: %s".formatted(who, String.join(", ", details.before().keySet())), details);
		}
		if (!bank.isEmpty()) {
			audit.record(AuditAction.BANK_DETAILS_CHANGED, Employee.AUDIT_TYPE, id,
					(employee.getBankAccount() == null) ? "Removed the salary account of " + who
							: "Changed the salary account of " + who,
					bank);
		}
		List<String> changed = new ArrayList<>(details.before().keySet());
		changed.addAll(bank.before().keySet());
		return new Saved(employee.fullName(), changed, invited);
	}

	// ------------------------------------------------------------------ checks

	private List<Problem> check(EmployeeForm form, Context context) {
		Options options = context.options();
		Map<String, String> found = new LinkedHashMap<>();
		// Rules only the database knows come first: they say more than the generic "Choose the department."
		pick(found, "departmentId", form.getDepartmentId(), options.departments(), "a department");
		pick(found, "designationId", form.getDesignationId(), options.designations(), "a designation");
		pick(found, "gradeId", form.getGradeId(), options.grades(), "a grade");
		pick(found, "locationId", form.getLocationId(), options.locations(), "a work location");
		pick(found, "costCentreId", form.getCostCentreId(), options.costCentres(), "a cost centre");
		Employee copy = new Employee(DRAFT_CODE);
		apply(form, copy, context);
		Long managerId = form.getReportingManagerId();
		if (managerId != null && !managerId.equals(form.getManagerOnFile())) {
			if (!options.managers().containsKey(managerId)) {
				found.put("reportingManagerId", "Choose someone from the list.");
			}
			else {
				try {
					Employee.checkReportingLine(copy, form.getId(), manager(managerId));
				}
				catch (BusinessRuleException loop) {
					found.put("reportingManagerId", loop.getMessage());
				}
			}
		}
		duplicates(form.getId(), copy, found);
		String account = Text.code(form.getAccountNumber());
		if (account != null && !account.equals(Text.code(form.getAccountNumberConfirm()))) {
			found.put("accountNumberConfirm", "The two account numbers don’t match. Type the number again.");
		}
		if (form.isCreateLogin() && form.getLoginOnFile() == null) {
			String problem = loginProblem(form.getLoginEmail());
			if (problem != null) {
				found.put("loginEmail", problem);
			}
		}
		for (ConstraintViolation<Employee> violation : validator.validate(copy)) {
			String path = violation.getPropertyPath().toString();
			String field = FIELDS.getOrDefault(path, path);
			if (!(form.isPermanentSame() && field.startsWith("permanent"))) {
				found.putIfAbsent(field, violation.getMessage());
			}
		}
		return sorted(found);
	}

	private void requireValid(EmployeeForm form, Context context) {
		List<Problem> problems = check(form, context);
		if (!problems.isEmpty()) {
			throw new BusinessRuleException(problems.getFirst().field(), problems.getFirst().message());
		}
	}

	/** A chosen organisation item must be one of the choices: active, or the one the employee already has. */
	private static void pick(Map<String, String> found, String field, Long id, Map<Long, String> choices,
			String noun) {
		if (id != null && !choices.containsKey(id)) {
			found.put(field, "Choose %s from the list.".formatted(noun));
		}
	}

	/** One person, one record: no one else may have the same personal e-mail, PAN, UAN or ESI IP number. */
	private void duplicates(Long id, Employee copy, Map<String, String> found) {
		// shortcut: personal e-mail checks can race; add a unique index if concurrent uniqueness must be guaranteed.
		if (copy.getPersonalEmail() != null) {
			employees.findByPersonalEmail(copy.getPersonalEmail())
				.stream()
				.filter(other -> !other.getId().equals(id))
				.findFirst()
				.ifPresent(other -> found.put("personalEmail", taken(other, "personal email")));
		}
		if (copy.getPan() != null) {
			employees.findByPan(copy.getPan(), hasher)
				.filter(other -> !other.getId().equals(id))
				.ifPresent(other -> found.put("pan", taken(other, "PAN")));
		}
		if (copy.getUan() != null) {
			employees.findByUan(copy.getUan())
				.filter(other -> !other.getId().equals(id))
				.ifPresent(other -> found.put("uan", taken(other, "UAN")));
		}
		if (copy.getEsiIpNumber() != null) {
			employees.findByEsiIpNumber(copy.getEsiIpNumber())
				.filter(other -> !other.getId().equals(id))
				.ifPresent(other -> found.put("esiIpNumber", taken(other, "ESI IP number")));
		}
	}

	private String loginProblem(String typed) {
		String email = User.normalizeEmail(Text.clean(typed));
		if (email == null) {
			return "Enter the email they will sign in with, or untick the sign-in account.";
		}
		if (email.length() > 254) {
			return "Use at most 254 characters.";
		}
		if (!EMAIL.matcher(email).matches()) {
			return "Enter an email like name@example.com.";
		}
		return users.existsByEmailIgnoreCase(email) ? "A Kosh user already signs in with this email. Use another one."
				: null;
	}

	private static String taken(Employee other, String what) {
		return "%s (%s) already has this %s. One person has one employee record.".formatted(other.fullName(),
				other.getCode(), what);
	}

	private static List<Problem> sorted(Map<String, String> found) {
		return found.entrySet()
			.stream()
			.map(entry -> new Problem(entry.getKey(), entry.getValue()))
			.sorted(Comparator.comparingInt(problem -> EmployeeStep.order(problem.field())))
			.toList();
	}

	// ------------------------------------------------------------------ saving

	/**
	 * Copies the form onto an employee: a new one, the stored one, or an unsaved copy to check. Everything but the
	 * reporting manager, whose rules the callers apply. Empty groups (address, contact, bank) become null.
	 */
	private void apply(EmployeeForm form, Employee employee, Context context) {
		Employee stored = context.stored();
		Options options = context.options();
		employee.setFirstName(form.getFirstName());
		employee.setMiddleName(form.getMiddleName());
		employee.setLastName(form.getLastName());
		employee.setGender(form.getGender());
		employee.setDateOfBirth(form.getDateOfBirth());
		employee.setPersonalEmail(form.getPersonalEmail());
		employee.setMobile(form.getMobile());
		Address current = address(form.getCurrentLine1(), form.getCurrentLine2(), form.getCurrentCity(),
				form.getCurrentState(), form.getCurrentPincode());
		employee.setCurrentAddress(current);
		employee.setPermanentAddress(form.isPermanentSame() ? current
				: address(form.getPermanentLine1(), form.getPermanentLine2(), form.getPermanentCity(),
						form.getPermanentState(), form.getPermanentPincode()));
		employee.setEmergencyContact(blank(form.getEmergencyName(), form.getEmergencyRelation(), form.getEmergencyPhone())
				? null : new EmergencyContact(form.getEmergencyName(), form.getEmergencyRelation(), form.getEmergencyPhone()));
		employee.setJoiningDate(form.getJoiningDate());
		employee.setEmploymentType(form.getEmploymentType(), form.getContractEndDate());
		employee.setConfirmationDate(form.getConfirmationDate());
		employee.setDepartment(reference(Department.class, form.getDepartmentId(), options.departments()));
		employee.setDesignation(reference(Designation.class, form.getDesignationId(), options.designations()));
		employee.setGrade(reference(Grade.class, form.getGradeId(), options.grades()));
		employee.setLocation(reference(WorkLocation.class, form.getLocationId(), options.locations()));
		employee.setCostCentre(reference(CostCentre.class, form.getCostCentreId(), options.costCentres()));
		employee.setPan(form.keepsPan() ? stored.getPan() : form.getPan(), hasher);
		employee.setUan(form.getUan());
		employee.setEsiIpNumber(form.getEsiIpNumber());
		employee.setPfPolicy(form.getPfPolicy());
		employee.setPtExemption(form.isPtExempt(), form.getPtExemptReason());
		employee.setPersonWithDisability(form.isPersonWithDisability());
		String account = form.getAccountNumber();
		if (form.keepsAccount()) {
			account = (stored.getBankAccount() == null) ? null : stored.getBankAccount().accountNumber();
		}
		employee.setBankAccount(form.bankEmpty() ? null
				: new BankAccount(form.getBankName(), form.getIfsc(), account, form.getHolderName()));
	}

	/** Inserts or updates at once, so a duplicate saved by someone else a moment ago fails here with a message. */
	private void save(Employee employee) {
		try {
			employees.saveAndFlush(employee);
		}
		catch (DataIntegrityViolationException race) {
			throw new BusinessRuleException(null, RACE);
		}
	}

	private String invite(Employee employee, EmployeeForm form, Authentication actor) {
		String name = Text.clean(employee.getFirstName() + " " + Objects.toString(employee.getLastName(), ""));
		try {
			User user = accounts.inviteEmployee(name.substring(0, Math.min(name.length(), 120)).strip(),
					form.getLoginEmail(), actor);
			employee.setUser(user);
			return user.getEmail();
		}
		catch (BusinessRuleException taken) {
			throw new BusinessRuleException("loginEmail", taken.getMessage());
		}
	}

	/**
	 * What the audit log keeps about an employee, labelled as on the form. PAN and account number are raw here and
	 * masked by {@link #compare}.
	 */
	private Map<String, String> values(Employee employee) {
		Map<String, String> values = new LinkedHashMap<>();
		values.put("Name", employee.fullName());
		values.put("Gender", (employee.getGender() == null) ? null : employee.getGender().label());
		values.put("Date of birth", date(employee.getDateOfBirth()));
		values.put("Personal email", employee.getPersonalEmail());
		values.put("Mobile", employee.getMobile());
		values.put("Current address", text(employee.getCurrentAddress()));
		values.put("Permanent address", text(employee.getPermanentAddress()));
		EmergencyContact contact = employee.getEmergencyContact();
		values.put("Emergency contact", (contact == null) ? null
				: "%s%s, %s".formatted(contact.name(), (contact.relation() == null) ? "" : " (" + contact.relation() + ")",
						contact.phone()));
		values.put("Department", name(employee.getDepartment()));
		values.put("Designation", name(employee.getDesignation()));
		values.put("Grade", name(employee.getGrade()));
		values.put("Work location", name(employee.getLocation()));
		values.put("Cost centre", name(employee.getCostCentre()));
		Employee manager = employee.getReportingManager();
		values.put("Reports to", (manager == null) ? null : "%s (%s)".formatted(manager.fullName(), manager.getCode()));
		values.put("Joining date", date(employee.getJoiningDate()));
		values.put("Employment type", (employee.getEmploymentType() == null) ? null : employee.getEmploymentType().label());
		values.put("Contract end date", date(employee.getContractEndDate()));
		values.put("Confirmation date", date(employee.getConfirmationDate()));
		values.put(PAN, employee.getPan());
		values.put("UAN", employee.getUan());
		values.put("ESI IP number", employee.getEsiIpNumber());
		values.put("PF policy", (employee.getPfPolicy() == null) ? null : employee.getPfPolicy().label());
		values.put("Professional tax", employee.isPtExempt() ? "Exempt: " + employee.getPtExemptReason() : "Deducted");
		values.put("Person with disability", employee.isPersonWithDisability() ? "Yes" : "No");
		BankAccount bank = employee.getBankAccount();
		values.put("Account holder", (bank == null) ? null : bank.holderName());
		values.put("Bank", (bank == null) ? null : bank.bankName());
		values.put("IFSC", (bank == null) ? null : bank.ifsc());
		values.put(ACCOUNT, (bank == null) ? null : bank.accountNumber());
		values.put("Sign-in account", (employee.getUser() == null) ? null : employee.getUser().getEmail());
		return values;
	}

	/** Adds a changed field, PAN and account number only as their masks. */
	private static void compare(AuditChanges changes, String label, String before, String after) {
		switch (label) {
			case PAN -> changes.pan(label, before, after);
			case ACCOUNT -> changes.account(label, before, after);
			default -> changes.field(label, before, after);
		}
	}

	// ------------------------------------------------------------------ helpers

	private Options options(EmployeeForm form, Employee stored) {
		Sort byName = Sort.by(Sort.Order.asc("name").ignoreCase());
		Map<Long, String> managers = new LinkedHashMap<>();
		for (Employee candidate : employees.findCurrent()) {
			if (!candidate.getId().equals(form.getId())) {
				managers.put(candidate.getId(), "%s (%s)".formatted(candidate.fullName(), candidate.getCode()));
			}
		}
		Employee manager = (stored == null) ? null : stored.getReportingManager();
		if (manager != null && !managers.containsKey(manager.getId())) {
			managers.put(manager.getId(), "%s (%s), has left".formatted(manager.fullName(), manager.getCode()));
		}
		return new Options(choices(OrgKind.DEPARTMENTS, byName, stored, Employee::getDepartment),
				choices(OrgKind.DESIGNATIONS, byName, stored, Employee::getDesignation),
				choices(OrgKind.GRADES, byName, stored, Employee::getGrade),
				choices(OrgKind.LOCATIONS, byName, stored, Employee::getLocation),
				choices(OrgKind.COST_CENTRES, byName, stored, Employee::getCostCentre), managers);
	}

	private Map<Long, String> choices(OrgKind kind, Sort sort, Employee stored, Function<Employee, OrgItem> current) {
		Long currentId = (stored == null) ? null : id(current.apply(stored));
		Map<Long, String> choices = new LinkedHashMap<>();
		for (OrgService.Row row : org.list(kind, sort)) {
			if (row.active() || Long.valueOf(row.id()).equals(currentId)) {
				choices.put(row.id(), row.active() ? row.name() : row.name() + " (inactive)");
			}
		}
		return choices;
	}

	private Employee stored(EmployeeForm form) {
		return form.isEditing() ? find(form.getId()) : null;
	}

	private Employee find(long id) {
		return employees.findWithDetailsById(id).orElseThrow(() -> new NotFoundException("No employee " + id));
	}

	private Employee manager(long id) {
		return employees.findById(id).orElseThrow(() -> new BusinessRuleException("reportingManagerId",
				"Choose someone from the list."));
	}

	private <T extends OrgItem> T reference(Class<T> type, Long id, Map<Long, String> choices) {
		return (id != null && choices.containsKey(id)) ? em.getReference(type, id) : null;
	}

	private static Address address(String line1, String line2, String city, IndianState state, String pincode) {
		return (blank(line1, line2, city, pincode) && state == null) ? null
				: new Address(line1, line2, city, state, pincode);
	}

	private static boolean blank(String... values) {
		for (String value : values) {
			if (Text.clean(value) != null) {
				return false;
			}
		}
		return true;
	}

	private static Long id(OrgItem item) {
		return (item == null) ? null : item.getId();
	}

	private static String name(OrgItem item) {
		return (item == null) ? null : item.getName();
	}

	private String date(LocalDate date) {
		return (date == null) ? null : fmt.date(date);
	}

	private static String text(Address address) {
		if (address == null) {
			return null;
		}
		StringBuilder text = new StringBuilder(address.line1());
		if (address.line2() != null) {
			text.append(", ").append(address.line2());
		}
		return text.append(", ").append(address.city()).append(", ").append(address.state().label()).append(' ')
			.append(address.pincode()).toString();
	}

}
