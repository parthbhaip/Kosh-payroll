package com.kosh.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import jakarta.persistence.EntityManager;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;

import org.hibernate.Hibernate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Transactional;

import com.kosh.common.BusinessRuleException;
import com.kosh.common.Gender;
import com.kosh.common.HmacHasher;
import com.kosh.common.IndianState;
import com.kosh.org.CostCentre;
import com.kosh.org.Department;
import com.kosh.org.Designation;
import com.kosh.org.Grade;
import com.kosh.org.OrgDetails;
import com.kosh.org.OrgKind;
import com.kosh.org.OrgService;
import com.kosh.org.WorkLocation;
import com.kosh.statutory.PfPolicy;
import com.kosh.user.Role;
import com.kosh.user.User;
import com.kosh.user.UserRepository;

/**
 * The employee model (PLAN T15) against the real schema from migration V8: every field stored and read back tidied,
 * PAN and bank account encrypted at rest with a PAN look-up hash, codes from the counter, unique keys, the CLAUDE.md
 * formats and the rules between fields (checked by Hibernate before any insert or update), reporting lines that never
 * loop, the queries that fetch organisation details in the same statement, the link to a sign-in account, and that the
 * organisation structure an employee uses can no longer be deleted. Each test runs in a rolled-back transaction (the
 * code counter rolls back with it).
 */
@SpringBootTest
@AutoConfigureMockMvc // same application context as the page tests, so no second start-up
@ActiveProfiles("test")
@Transactional
class EmployeeModelTest {

	/** The fixed test clock: 5 Oct 2026, 10:00 in Kolkata. */
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 5, 10, 0);

	private static final LocalDate JOINED = LocalDate.of(2024, 4, 1);

	private final EmployeeRepository employees;

	private final OrgService org;

	private final UserRepository users;

	private final HmacHasher hasher;

	private final Validator validator;

	private final JdbcTemplate jdbc;

	private final EntityManager em;

	private Department production;

	private Designation operator;

	private Grade grade;

	private CostCentre costCentre;

	private WorkLocation shiroli;

	@Autowired
	EmployeeModelTest(EmployeeRepository employees, OrgService org, UserRepository users, HmacHasher hasher,
			Validator validator, JdbcTemplate jdbc, EntityManager em) {
		this.employees = employees;
		this.org = org;
		this.users = users;
		this.hasher = hasher;
		this.validator = validator;
		this.jdbc = jdbc;
		this.em = em;
	}

	@BeforeEach
	void organisation() {
		asHr();
		production = em.find(Department.class, org.create(OrgKind.DEPARTMENTS, OrgDetails.of("T15-PROD", "T15 Production")));
		operator = em.find(Designation.class, org.create(OrgKind.DESIGNATIONS, OrgDetails.of("T15-OPR", "T15 Machine Operator")));
		grade = em.find(Grade.class, org.create(OrgKind.GRADES, OrgDetails.of("T15-G2", "T15 Operators")));
		costCentre = em.find(CostCentre.class, org.create(OrgKind.COST_CENTRES, OrgDetails.of("T15-CC", "T15 Shiroli plant")));
		shiroli = em.find(WorkLocation.class, org.create(OrgKind.LOCATIONS, new OrgDetails("T15-SHR", "T15 Shiroli factory",
				null, "Plot B-12, MIDC Shiroli", null, "Kolhapur", IndianState.MH, "416122", true)));
	}

	@AfterEach
	void signOut() {
		SecurityContextHolder.clearContext();
	}

	// ------------------------------------------------------------------ storing and reading back

	@Test
	void everyFieldIsStoredTidiedAndReadBack() {
		Employee manager = employees.saveAndFlush(employee("Ravi", "Kale"));
		User account = users.save(new User("sunita.deshmukh@t15.kosh.test", "Sunita Deshmukh", List.of(Role.EMPLOYEE)));

		Employee sunita = employee("  Sunita ", "Deshmukh");
		sunita.setMiddleName("  ");
		sunita.setGender(Gender.FEMALE);
		sunita.setDateOfBirth(LocalDate.of(1993, 2, 11));
		sunita.setPersonalEmail(" Sunita.D@Example.COM ");
		sunita.setMobile("98220 12345");
		sunita.setCurrentAddress(new Address(" Flat 4, Shree Apartments ", " Rajarampuri  5th Lane", "Kolhapur",
				IndianState.MH, "416 008"));
		sunita.setPermanentAddress(new Address("At post Vadgaon", null, "Hatkanangale", IndianState.MH, "416112"));
		sunita.setEmergencyContact(new EmergencyContact("Mahesh Deshmukh", "Husband", "98901 12233"));
		sunita.setJoiningDate(LocalDate.of(2019, 7, 1));
		sunita.setConfirmationDate(LocalDate.of(2020, 1, 1));
		sunita.setGrade(grade);
		sunita.setCostCentre(costCentre);
		sunita.setReportingManager(manager);
		sunita.setUser(account);
		sunita.setPan(" abcde 1234f ", hasher);
		sunita.setUan("1009 8765 4321");
		sunita.setEsiIpNumber("3100123456");
		sunita.setPfPolicy(PfPolicy.ACTUAL);
		sunita.setPtExemption(true, "  Certified disability of 40% or more ");
		sunita.setPersonWithDisability(true);
		sunita.setBankAccount(new BankAccount(" State Bank of India ", "sbin0001234", "0012 3456 7890 4321",
				"Sunita Deshmukh"));
		Long id = employees.saveAndFlush(sunita).getId();
		em.clear();

		Employee loaded = employees.findWithDetailsById(id).orElseThrow();
		assertThat(loaded.getCode()).matches("KSH-\\d{4,}");
		assertThat(loaded.getFirstName()).isEqualTo("Sunita");
		assertThat(loaded.getMiddleName()).isNull();
		assertThat(loaded.fullName()).isEqualTo("Sunita Deshmukh");
		assertThat(loaded.getGender()).isEqualTo(Gender.FEMALE);
		assertThat(loaded.getDateOfBirth()).isEqualTo(LocalDate.of(1993, 2, 11));
		assertThat(loaded.getPersonalEmail()).isEqualTo("sunita.d@example.com");
		assertThat(loaded.getMobile()).isEqualTo("9822012345");
		assertThat(loaded.getCurrentAddress()).isEqualTo(new Address("Flat 4, Shree Apartments", "Rajarampuri 5th Lane",
				"Kolhapur", IndianState.MH, "416008"));
		assertThat(loaded.getPermanentAddress().line2()).isNull();
		assertThat(loaded.getEmergencyContact()).isEqualTo(new EmergencyContact("Mahesh Deshmukh", "Husband", "9890112233"));
		assertThat(loaded.getJoiningDate()).isEqualTo(LocalDate.of(2019, 7, 1));
		assertThat(loaded.getConfirmationDate()).isEqualTo(LocalDate.of(2020, 1, 1));
		assertThat(loaded.getEmploymentType()).isEqualTo(EmploymentType.PERMANENT);
		assertThat(loaded.getContractEndDate()).isNull();
		assertThat(loaded.getStatus()).isEqualTo(EmployeeStatus.ACTIVE);
		assertThat(loaded.getDepartment().getName()).isEqualTo("T15 Production");
		assertThat(loaded.getDesignation().getName()).isEqualTo("T15 Machine Operator");
		assertThat(loaded.getGrade().getName()).isEqualTo("T15 Operators");
		assertThat(loaded.getLocation().getName()).isEqualTo("T15 Shiroli factory");
		assertThat(loaded.getCostCentre().getName()).isEqualTo("T15 Shiroli plant");
		assertThat(loaded.getReportingManager().fullName()).isEqualTo("Ravi Kale");
		assertThat(loaded.getUser().getEmail()).isEqualTo("sunita.deshmukh@t15.kosh.test");
		assertThat(loaded.getPan()).isEqualTo("ABCDE1234F");
		assertThat(loaded.getUan()).isEqualTo("100987654321");
		assertThat(loaded.getEsiIpNumber()).isEqualTo("3100123456");
		assertThat(loaded.getPfPolicy()).isEqualTo(PfPolicy.ACTUAL);
		assertThat(loaded.isPtExempt()).isTrue();
		assertThat(loaded.getPtExemptReason()).isEqualTo("Certified disability of 40% or more");
		assertThat(loaded.isPersonWithDisability()).isTrue();
		assertThat(loaded.getBankAccount()).isEqualTo(new BankAccount("State Bank of India", "SBIN0001234",
				"0012345678904321", "Sunita Deshmukh"));
		assertThat(loaded.getCreatedAt()).isEqualTo(NOW);
	}

	@Test
	void missingOptionalGroupsReadBackAsNothing() {
		Long id = employees.saveAndFlush(employee("Ganesh", null)).getId();
		em.clear();

		Employee loaded = employees.findById(id).orElseThrow();
		assertThat(loaded.fullName()).isEqualTo("Ganesh");
		assertThat(loaded.getCurrentAddress()).isNull();
		assertThat(loaded.getPermanentAddress()).isNull();
		assertThat(loaded.getEmergencyContact()).isNull();
		assertThat(loaded.getBankAccount()).isNull();
		assertThat(loaded.getPan()).isNull();
		assertThat(rawColumn(id, "pan_hash")).isNull();
		assertThat(loaded.isPtExempt()).isFalse();
		assertThat(loaded.getPtExemptReason()).isNull();
	}

	@Test
	void panAndAccountNumberAreEncryptedAtRestAndThePanIsFoundByItsHash() {
		Employee sunita = employee("Sunita", "Deshmukh");
		sunita.setPan("ABCDE1234F", hasher);
		sunita.setBankAccount(new BankAccount("State Bank of India", "SBIN0001234", "001234567894321", "Sunita Deshmukh"));
		Long id = employees.saveAndFlush(sunita).getId();
		em.clear();

		assertThat(rawColumn(id, "pan")).isNotBlank().doesNotContain("ABCDE").doesNotContain("1234");
		assertThat(rawColumn(id, "bank_account_number")).isNotBlank().doesNotContain("4321").doesNotContain("123456");
		assertThat(rawColumn(id, "pan_hash")).isEqualTo(hasher.hash("ABCDE1234F")).hasSize(64);
		assertThat(employees.findByPan(" abcde 1234f", hasher)).map(Employee::getId).contains(id);
		assertThat(employees.findByPan("PQRST6789K", hasher)).isEmpty();
		assertThat(employees.findByPan("  ", hasher)).isEmpty();

		Employee loaded = employees.findById(id).orElseThrow();
		loaded.setPan("pqrst6789k", hasher);
		employees.flush();
		em.clear();
		assertThat(employees.findByPan("PQRST6789K", hasher)).map(Employee::getId).contains(id);
		assertThat(employees.findByPan("ABCDE1234F", hasher)).isEmpty();

		employees.findById(id).orElseThrow().setPan(null, hasher);
		employees.flush();
		assertThat(rawColumn(id, "pan")).isNull();
		assertThat(rawColumn(id, "pan_hash")).isNull();
	}

	@Test
	void bankDetailsNeverPrintTheAccountNumber() {
		BankAccount account = new BankAccount("State Bank of India", "SBIN0001234", "001234567894321", "Sunita Deshmukh");
		assertThat(account.toString()).contains("XXXXXX4321").doesNotContain("0012345678");
	}

	// ------------------------------------------------------------------ codes

	@Test
	void codesComeFromTheCounterInOrderAndGrowPastFourDigits() {
		String first = employees.nextCode();
		String second = employees.nextCode();

		assertThat(first).matches("KSH-\\d{4,}");
		assertThat(number(second)).isEqualTo(number(first) + 1);
		assertThat(jdbc.queryForObject("select last_number from employee_code_counter", Integer.class))
			.isEqualTo(number(second));
		assertThat(Employee.code(1)).isEqualTo("KSH-0001");
		assertThat(Employee.code(42)).isEqualTo("KSH-0042");
		assertThat(Employee.code(12345)).isEqualTo("KSH-12345");
	}

	@Test
	void aCodeIsOnlyHandedOutInsideTheTransactionThatSavesTheEmployee() {
		TestTransaction.end();

		assertThatThrownBy(employees::nextCode).isInstanceOf(IllegalTransactionStateException.class);
	}

	@ParameterizedTest
	@EnumSource(Gender.class)
	void everyGenderIsStoredByName(Gender gender) {
		Employee employee = employee("Kiran", "Pawar");
		employee.setGender(gender);
		Long id = employees.saveAndFlush(employee).getId();
		em.clear();

		assertThat(rawColumn(id, "gender")).isEqualTo(gender.name());
		assertThat(employees.findById(id).orElseThrow().getGender()).isEqualTo(gender);
	}

	// ------------------------------------------------------------------ unique keys

	@ParameterizedTest
	@ValueSource(strings = { "code", "pan", "uan", "esiIpNumber", "user" })
	void theDatabaseRefusesTheSameIdentifierTwice(String field) {
		User account = users.save(new User("ravi.kale@t15.kosh.test", "Ravi Kale", List.of(Role.EMPLOYEE)));
		Employee first = employee("Ravi", "Kale");
		first.setPan("ABCDE1234F", hasher);
		first.setUan("100987654321");
		first.setEsiIpNumber("3100123456");
		first.setUser(account);
		employees.saveAndFlush(first);

		Employee second = field.equals("code") ? fill(new Employee(first.getCode()), "Asha", "Patil")
				: employee("Asha", "Patil");
		switch (field) {
			case "pan" -> second.setPan("abcde1234f", hasher);
			case "uan" -> second.setUan("100987654321");
			case "esiIpNumber" -> second.setEsiIpNumber("3100123456");
			case "user" -> second.setUser(account);
			default -> {
			}
		}
		assertThatThrownBy(() -> employees.saveAndFlush(second)).isInstanceOf(DataIntegrityViolationException.class);
	}

	// ------------------------------------------------------------------ validation

	@Test
	void requiredFieldsAreReportedOnTheirOwnFields() {
		assertThat(problems(new Employee("KSH-9999"))).containsExactlyInAnyOrderEntriesOf(Map.of(
				"firstName", "Enter the first name.",
				"gender", "Choose the gender.",
				"dateOfBirth", "Enter the date of birth.",
				"mobile", "Enter the mobile number.",
				"joiningDate", "Enter the joining date.",
				"employmentType", "Choose the employment type.",
				"department", "Choose the department.",
				"designation", "Choose the designation.",
				"location", "Choose the work location.",
				"pfPolicy", "Choose the PF policy."));
		assertThat(problems(employee("Ravi", "Kale"))).isEmpty();
	}

	@Test
	void identifiersFollowTheIndianFormats() {
		Employee ravi = employee("Ravi", "Kale");
		ravi.setPan("ABCD1234F", hasher);
		ravi.setUan("10098765432");
		ravi.setEsiIpNumber("31001234567");
		ravi.setMobile("5822012345");
		ravi.setPersonalEmail("ravi@");
		ravi.setCurrentAddress(new Address("Plot 7", null, "Kolhapur", IndianState.MH, "016001"));
		ravi.setEmergencyContact(new EmergencyContact("Asha Kale", null, "982201234"));
		ravi.setBankAccount(new BankAccount("State Bank of India", "SBIN1001234", "12345678", "Ravi Kale"));

		assertThat(problems(ravi)).containsExactlyInAnyOrderEntriesOf(Map.of(
				"pan", "Enter a PAN like ABCDE1234F: five letters, four digits, a letter.",
				"uan", "Enter the 12-digit UAN.",
				"esiIpNumber", "Enter the 10-digit ESI IP number.",
				"mobile", "Enter a 10-digit mobile number starting with 6, 7, 8 or 9.",
				"personalEmail", "Enter an email address like name@example.com.",
				"currentAddress.pincode", "Enter a 6-digit PIN code.",
				"emergencyContact.phone", "Enter a 10-digit mobile number starting with 6, 7, 8 or 9.",
				"bankAccount.ifsc", "Enter an IFSC like SBIN0001234: four letters, a zero, then six letters or digits.",
				"bankAccount.accountNumber", "Enter an account number of 9 to 18 digits."));

		Employee asha = employee("Asha", "Patil");
		asha.setPersonalEmail("asha@kolhapur");
		assertThat(problems(asha)).containsOnlyKeys("personalEmail");
		asha.setPersonalEmail("asha@kolhapur.in");
		assertThat(problems(asha)).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = { "6000000000", "7999999999", "8123456789", "9822012345" })
	void mobileNumbersStartWithSixToNine(String mobile) {
		Employee ravi = employee("Ravi", "Kale");
		ravi.setMobile(mobile);
		assertThat(problems(ravi)).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = { "5822012345", "0822012345", "982201234", "98220123456", "98220-1234", "+919822012345" })
	void otherMobileNumbersAreRefused(String mobile) {
		Employee ravi = employee("Ravi", "Kale");
		ravi.setMobile(mobile);
		assertThat(problems(ravi)).containsOnlyKeys("mobile");
	}

	@Test
	void validIdentifiersAtTheEdgesPass() {
		Employee ravi = employee("Ravi", "Kale");
		ravi.setPan("ZZZZZ0000Z", hasher);
		ravi.setUan("000000000001");
		ravi.setEsiIpNumber("0000000001");
		ravi.setCurrentAddress(new Address("Plot 7", null, "Kolhapur", IndianState.MH, "999999"));
		ravi.setBankAccount(new BankAccount("HDFC Bank", "hdfc0ab12c3", "123456789", "Ravi Kale"));
		assertThat(problems(ravi)).isEmpty();

		ravi.setBankAccount(new BankAccount("HDFC Bank", "HDFC0AB12C3", "123456789012345678", "Ravi Kale"));
		assertThat(problems(ravi)).isEmpty();
		ravi.setBankAccount(new BankAccount("HDFC Bank", "HDFC0AB12C3", "1234567890123456789", "Ravi Kale"));
		assertThat(problems(ravi)).containsOnlyKeys("bankAccount.accountNumber");
	}

	@Test
	void aPartlyFilledGroupNamesWhatIsMissing() {
		Employee ravi = employee("Ravi", "Kale");
		ravi.setCurrentAddress(new Address("Plot 7", "  ", " ", null, null));
		ravi.setEmergencyContact(new EmergencyContact("Asha Kale", "Wife", null));
		ravi.setBankAccount(new BankAccount("State Bank of India", null, " ", null));

		assertThat(problems(ravi)).containsExactlyInAnyOrderEntriesOf(Map.of(
				"currentAddress.city", "Enter the city.",
				"currentAddress.state", "Choose the state.",
				"currentAddress.pincode", "Enter the PIN code.",
				"emergencyContact.phone", "Enter the contact’s mobile number.",
				"bankAccount.ifsc", "Enter the IFSC.",
				"bankAccount.accountNumber", "Enter the account number.",
				"bankAccount.holderName", "Enter the account holder’s name."));
	}

	@Test
	void textOverItsColumnSizeIsRefused() {
		Employee ravi = employee("R".repeat(61), "K".repeat(61));
		ravi.setMiddleName("M".repeat(61));
		ravi.setPtExemption(true, "x".repeat(201));

		assertThat(problems(ravi)).containsExactlyInAnyOrderEntriesOf(Map.of(
				"firstName", "Use at most 60 characters.",
				"middleName", "Use at most 60 characters.",
				"lastName", "Use at most 60 characters.",
				"ptExemptReason", "Use at most 200 characters."));
	}

	@Test
	void theEmployeeIsAtLeastFourteenAndUnderAHundredOnTheJoiningDate() {
		Employee young = employee("Ravi", "Kale");
		young.setDateOfBirth(JOINED.minusYears(14));
		assertThat(problems(young)).isEmpty();
		young.setDateOfBirth(JOINED.minusYears(14).plusDays(1));
		assertThat(problems(young)).containsExactlyEntriesOf(Map.of("dateOfBirth",
				"The employee must be at least 14 on the joining date."));

		young.setDateOfBirth(JOINED.minusYears(100).plusDays(1));
		assertThat(problems(young)).isEmpty();
		young.setDateOfBirth(JOINED.minusYears(100));
		assertThat(problems(young)).containsExactlyEntriesOf(Map.of("dateOfBirth",
				"Check the year: this makes the employee 100 or older on the joining date."));
	}

	@Test
	void datesFollowTheJoiningDateAndTheEmploymentType() {
		Employee ravi = employee("Ravi", "Kale");
		ravi.setConfirmationDate(JOINED);
		assertThat(problems(ravi)).isEmpty();
		ravi.setConfirmationDate(JOINED.minusDays(1));
		assertThat(problems(ravi)).containsExactlyEntriesOf(Map.of("confirmationDate",
				"The confirmation date can’t be before the joining date."));
		ravi.setConfirmationDate(null);

		ravi.setEmploymentType(EmploymentType.FIXED_TERM, null);
		assertThat(problems(ravi)).containsExactlyEntriesOf(Map.of("contractEndDate",
				"Enter the contract end date for a fixed-term employee."));
		ravi.setEmploymentType(EmploymentType.FIXED_TERM, JOINED);
		assertThat(problems(ravi)).containsExactlyEntriesOf(Map.of("contractEndDate",
				"The contract must end after the joining date."));
		ravi.setEmploymentType(EmploymentType.FIXED_TERM, JOINED.plusMonths(14));
		assertThat(problems(ravi)).isEmpty();

		ravi.setEmploymentType(EmploymentType.INTERN, null);
		assertThat(problems(ravi)).isEmpty();
		ravi.setEmploymentType(EmploymentType.INTERN, JOINED.plusMonths(6));
		assertThat(problems(ravi)).isEmpty();

		for (EmploymentType type : List.of(EmploymentType.PERMANENT, EmploymentType.PROBATION)) {
			ravi.setEmploymentType(type, JOINED.plusYears(1));
			assertThat(problems(ravi)).containsExactlyEntriesOf(Map.of("contractEndDate",
					"Only fixed-term employees and interns have a contract end date."));
		}
	}

	@Test
	void aProfessionalTaxExemptionNeedsAReasonAndDropsItWhenLifted() {
		Employee ravi = employee("Ravi", "Kale");
		ravi.setPtExemption(true, "  ");
		assertThat(problems(ravi)).containsExactlyEntriesOf(Map.of("ptExemptReason",
				"Give the reason for the professional tax exemption."));

		ravi.setPtExemption(false, "Serving in the armed forces");
		assertThat(ravi.getPtExemptReason()).isNull();
		assertThat(problems(ravi)).isEmpty();
	}

	@Test
	void pfCanOnlyBeExcludedForSomeoneWithoutAUan() {
		Employee ravi = employee("Ravi", "Kale");
		ravi.setPfPolicy(PfPolicy.EXCLUDED);
		assertThat(problems(ravi)).isEmpty();

		ravi.setUan("100987654321");
		assertThat(problems(ravi)).containsExactlyEntriesOf(Map.of("pfPolicy",
				"Someone with a UAN is already a PF member, so PF can’t be excluded."));
	}

	@Test
	void hibernateRefusesAnInvalidEmployeeWhoeverSavesIt() {
		Employee ravi = employee("Ravi", "Kale");
		ravi.setPan("ABCD1234F", hasher);
		assertThatThrownBy(() -> employees.saveAndFlush(ravi)).isInstanceOf(ConstraintViolationException.class)
			.hasMessageContaining("pan");

		em.clear();
		Employee asha = employees.saveAndFlush(employee("Asha", "Patil"));
		asha.setEmploymentType(EmploymentType.FIXED_TERM, null);
		assertThatThrownBy(employees::flush).isInstanceOf(ConstraintViolationException.class)
			.hasMessageContaining("contractEndDate");
	}

	// ------------------------------------------------------------------ reporting lines

	@Test
	void aReportingLineNeverLoops() {
		Employee ravi = employees.saveAndFlush(employee("Ravi", "Kale"));
		Employee asha = employee("Asha", "Patil");
		asha.setReportingManager(ravi);
		employees.saveAndFlush(asha);
		Employee vikram = employee("Vikram", "Jadhav");
		vikram.setReportingManager(asha);
		employees.saveAndFlush(vikram);

		assertThatThrownBy(() -> ravi.setReportingManager(ravi)).isInstanceOf(BusinessRuleException.class)
			.hasMessage("Choose someone else: an employee can’t report to themselves.")
			.extracting("field")
			.isEqualTo("reportingManager");
		assertThatThrownBy(() -> ravi.setReportingManager(vikram)).isInstanceOf(BusinessRuleException.class)
			.hasMessage("Choose someone else: Vikram Jadhav already reports to Ravi Kale.");

		em.clear();
		Employee loadedRavi = employees.findById(ravi.getId()).orElseThrow();
		Employee vikramReference = employees.getReferenceById(vikram.getId());
		assertThatThrownBy(() -> loadedRavi.setReportingManager(vikramReference))
			.isInstanceOf(BusinessRuleException.class);
		assertThatThrownBy(() -> loadedRavi.setReportingManager(employees.getReferenceById(ravi.getId())))
			.isInstanceOf(BusinessRuleException.class);
		assertThat(loadedRavi.getReportingManager()).isNull();

		Employee newcomer = employee("Neha", "Shinde");
		newcomer.setReportingManager(vikramReference);
		assertThat(newcomer.getReportingManager().getId()).isEqualTo(vikram.getId());

		Employee left = employee("Sanjay", "More");
		left.setStatus(EmployeeStatus.EXITED);
		employees.saveAndFlush(left);
		assertThatThrownBy(() -> newcomer.setReportingManager(left)).isInstanceOf(BusinessRuleException.class)
			.hasMessage("Choose someone else: Sanjay More has left the company.")
			.extracting("field")
			.isEqualTo("reportingManager");
		assertThat(newcomer.getReportingManager().getId()).isEqualTo(vikram.getId());
		Employee onNotice = employee("Kiran", "Pawar");
		onNotice.setStatus(EmployeeStatus.ON_NOTICE);
		newcomer.setReportingManager(employees.saveAndFlush(onNotice));
		newcomer.setReportingManager(null);
		assertThat(newcomer.getReportingManager()).isNull();
	}

	// ------------------------------------------------------------------ queries

	@Test
	void theProfileQueryLoadsOrganisationManagerAndAccountTogether() {
		Employee ravi = employees.saveAndFlush(employee("Ravi", "Kale"));
		Employee asha = employee("Asha", "Patil");
		asha.setGrade(grade);
		asha.setCostCentre(costCentre);
		asha.setReportingManager(ravi);
		asha.setUser(users.save(new User("asha.patil@t15.kosh.test", "Asha Patil", List.of(Role.EMPLOYEE))));
		Long id = employees.saveAndFlush(asha).getId();
		em.clear();

		Employee loaded = employees.findWithDetailsById(id).orElseThrow();
		assertThat(List.of(loaded.getDepartment(), loaded.getDesignation(), loaded.getGrade(), loaded.getLocation(),
				loaded.getCostCentre(), loaded.getReportingManager(), loaded.getUser()))
			.allMatch(Hibernate::isInitialized);

		em.clear();
		Employee plain = employees.findById(id).orElseThrow();
		assertThat(Hibernate.isInitialized(plain.getDepartment())).isFalse();
	}

	@Test
	void listPagesFilterAndLoadTheirColumnsInTheSameQuery() {
		Employee ravi = employees.saveAndFlush(employee("Ravi", "Kale"));
		for (String name : List.of("Vikram", "Anjali", "Mohan")) {
			Employee report = employee(name, "Jadhav");
			report.setReportingManager(ravi);
			employees.save(report);
		}
		Employee left = employee("Sanjay", "More");
		left.setStatus(EmployeeStatus.EXITED);
		employees.saveAndFlush(left);
		em.clear();

		Page<Employee> page = employees.findAll(
				(root, query, cb) -> cb.and(cb.equal(root.get("department"), production),
						cb.notEqual(root.get("status"), EmployeeStatus.EXITED)),
				PageRequest.of(0, 3, Sort.by("firstName", "lastName")));

		assertThat(page.getTotalElements()).isEqualTo(4);
		assertThat(page.getContent()).extracting(Employee::getFirstName).containsExactly("Anjali", "Mohan", "Ravi");
		assertThat(page.getContent()).allSatisfy(employee -> assertThat(List.of(employee.getDepartment(),
				employee.getDesignation(), employee.getLocation())).allMatch(Hibernate::isInitialized));
		assertThat(page.getContent().getFirst().getReportingManager()).matches(Hibernate::isInitialized);
	}

	@Test
	void aManagersTeamIsTheirCurrentDirectReportsByName() {
		Employee ravi = employees.saveAndFlush(employee("Ravi", "Kale"));
		Employee asha = employees.saveAndFlush(employee("Asha", "Patil"));
		for (String name : List.of("Vikram", "Anjali", "Sanjay")) {
			Employee report = employee(name, "Jadhav");
			report.setReportingManager(ravi);
			report.setStatus(name.equals("Sanjay") ? EmployeeStatus.EXITED : EmployeeStatus.ACTIVE);
			employees.save(report);
		}
		Employee onNotice = employee("Kiran", "Pawar");
		onNotice.setReportingManager(ravi);
		onNotice.setStatus(EmployeeStatus.ON_NOTICE);
		employees.save(onNotice);
		Employee other = employee("Meera", "Joshi");
		other.setReportingManager(asha);
		employees.saveAndFlush(other);
		em.clear();

		List<Employee> team = employees.findTeam(ravi.getId());
		assertThat(team).extracting(Employee::fullName).containsExactly("Anjali Jadhav", "Kiran Pawar", "Vikram Jadhav");
		assertThat(team).allSatisfy(employee -> assertThat(List.of(employee.getDepartment(), employee.getDesignation(),
				employee.getLocation())).allMatch(Hibernate::isInitialized));
		assertThat(employees.findTeam(other.getId())).isEmpty();
	}

	@Test
	void theSignedInUsersEmployeeRecordIsFoundByEmailInAnyCase() {
		Employee ravi = employee("Ravi", "Kale");
		ravi.setUser(users.save(new User("ravi.kale@t15.kosh.test", "Ravi Kale", List.of(Role.EMPLOYEE, Role.MANAGER))));
		Long id = employees.saveAndFlush(ravi).getId();
		users.save(new User("no.record@t15.kosh.test", "No Record", List.of(Role.EMPLOYEE)));
		em.clear();

		Employee found = employees.findByUserEmail("  Ravi.Kale@T15.kosh.TEST ").orElseThrow();
		assertThat(found.getId()).isEqualTo(id);
		assertThat(Hibernate.isInitialized(found.getDepartment())).isTrue();
		assertThat(employees.findByUserEmail("no.record@t15.kosh.test")).isEmpty();
		assertThat(employees.findByUserEmail(" ")).isEmpty();
		assertThat(employees.findByUserEmail(null)).isEmpty();
	}

	// ------------------------------------------------------------------ the organisation structure in use

	@ParameterizedTest
	@EnumSource(OrgKind.class)
	void organisationItemsAnEmployeeUsesCanNoLongerBeDeleted(OrgKind kind) {
		Employee ravi = employee("Ravi", "Kale");
		ravi.setGrade(grade);
		ravi.setCostCentre(costCentre);
		employees.saveAndFlush(ravi);
		em.clear();

		long id = switch (kind) {
			case DEPARTMENTS -> production.getId();
			case DESIGNATIONS -> operator.getId();
			case GRADES -> grade.getId();
			case COST_CENTRES -> costCentre.getId();
			case LOCATIONS -> shiroli.getId();
		};
		assertThatThrownBy(() -> org.delete(kind, id)).isInstanceOf(BusinessRuleException.class)
			.hasMessageEndingWith("is in use, so it can’t be deleted");
	}

	// ------------------------------------------------------------------ helpers

	/** A valid permanent employee at the T15 factory, not saved yet. */
	private Employee employee(String firstName, String lastName) {
		return fill(new Employee(employees.nextCode()), firstName, lastName);
	}

	private Employee fill(Employee employee, String firstName, String lastName) {
		employee.setFirstName(firstName);
		employee.setLastName(lastName);
		employee.setGender(Gender.MALE);
		employee.setDateOfBirth(LocalDate.of(1990, 6, 15));
		employee.setMobile("9822012345");
		employee.setJoiningDate(JOINED);
		employee.setEmploymentType(EmploymentType.PERMANENT, null);
		employee.setDepartment(production);
		employee.setDesignation(operator);
		employee.setLocation(shiroli);
		employee.setPfPolicy(PfPolicy.RESTRICT);
		return employee;
	}

	/** Every broken rule, by field path. */
	private Map<String, String> problems(Employee employee) {
		Map<String, String> problems = new TreeMap<>();
		for (ConstraintViolation<Employee> violation : validator.validate(employee)) {
			problems.put(violation.getPropertyPath().toString(), violation.getMessage());
		}
		return problems;
	}

	private String rawColumn(Long id, String column) {
		return jdbc.queryForObject("select " + column + " from employees where id = ?", String.class, id);
	}

	private static int number(String code) {
		return Integer.parseInt(code.substring(Employee.CODE_PREFIX.length()));
	}

	private static void asHr() {
		SecurityContextHolder.getContext()
			.setAuthentication(new TestingAuthenticationToken("hr@t15.kosh.test", null, "ROLE_HR"));
	}

}
