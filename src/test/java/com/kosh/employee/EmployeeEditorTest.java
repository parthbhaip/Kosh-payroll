package com.kosh.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mail.MailSendException;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.kosh.audit.AuditAction;
import com.kosh.audit.AuditEvent;
import com.kosh.audit.AuditEventRepository;
import com.kosh.common.Gender;
import com.kosh.common.HmacHasher;
import com.kosh.common.IndianState;
import com.kosh.notification.MailService;
import com.kosh.org.Department;
import com.kosh.org.Designation;
import com.kosh.org.OrgDetails;
import com.kosh.org.OrgKind;
import com.kosh.org.OrgService;
import com.kosh.org.WorkLocation;
import com.kosh.statutory.PfPolicy;
import com.kosh.user.Role;
import com.kosh.user.User;
import com.kosh.user.UserAdminService;
import com.kosh.user.UserRepository;
import com.kosh.web.Flash;

/**
 * Adding and editing employees through the five-step form (PLAN T17): each step checked before the next opens, the
 * draft kept in the session, duplicates refused next to their fields, the code from the counter, an optional sign-in
 * account with an invite, edits audited with PAN and account numbers masked, and who may do any of it. Mail is a spy
 * on the log transport. Every test runs in a rolled-back transaction.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@MockitoSpyBean(types = MailService.class)
class EmployeeEditorTest {

	private static final String HR = "hr@t17.kosh.test";

	private static final String ADMIN = "admin@t17.kosh.test";

	private static final String NEW = "/hr/employees/new";

	private static final String DRAFT = "employee-draft:new";

	private static final String PAN = "PQRST6789K";

	private static final String ACCOUNT = "123456784321";

	private final MockMvc mvc;

	private final EmployeeRepository employees;

	private final EmployeeService service;

	private final UserAdminService accounts;

	private final OrgService org;

	private final UserRepository users;

	private final AuditEventRepository events;

	private final MailService mail;

	private final HmacHasher hasher;

	private final EntityManager em;

	private MockHttpSession session;

	private Department production;

	private Department stores;

	private Designation operator;

	private WorkLocation shiroli;

	/** Someone already on the rolls: PAN ABCDE1234F, UAN, ESI number, personal e-mail and bank details. */
	private Employee ravi;

	@Autowired
	EmployeeEditorTest(MockMvc mvc, EmployeeRepository employees, EmployeeService service, UserAdminService accounts,
			OrgService org, UserRepository users, AuditEventRepository events, MailService mail, HmacHasher hasher,
			EntityManager em) {
		this.mvc = mvc;
		this.employees = employees;
		this.service = service;
		this.accounts = accounts;
		this.org = org;
		this.users = users;
		this.events = events;
		this.mail = mail;
		this.hasher = hasher;
		this.em = em;
	}

	@BeforeEach
	void organisation() {
		asHr();
		session = new MockHttpSession();
		production = em.find(Department.class, org.create(OrgKind.DEPARTMENTS, OrgDetails.of("T17-PROD", "T17 Production")));
		stores = em.find(Department.class, org.create(OrgKind.DEPARTMENTS, OrgDetails.of("T17-STR", "T17 Stores")));
		operator = em.find(Designation.class, org.create(OrgKind.DESIGNATIONS, OrgDetails.of("T17-OPR", "T17 Machine Operator")));
		shiroli = em.find(WorkLocation.class, org.create(OrgKind.LOCATIONS, new OrgDetails("T17-SHR", "T17 Shiroli factory",
				null, "Plot B-12, MIDC Shiroli", null, "Kolhapur", IndianState.MH, "416122", true)));
		ravi = hire("Ravi", "Kale");
		ravi.setPersonalEmail("ravi.kale@t17.example.com");
		ravi.setPan("ABCDE1234F", hasher);
		ravi.setUan("100987654321");
		ravi.setEsiIpNumber("3100456789");
		ravi.setBankAccount(new BankAccount("Bank of Maharashtra", "MAHB0000123", "998877660001", "Ravi Kale"));
		employees.flush();
	}

	@AfterEach
	void signOut() {
		SecurityContextHolder.clearContext();
	}

	// ------------------------------------------------------------------ adding, step by step

	@Test
	void hrAddsAnEmployeeStepByStepWithASignInAccountAndAnInvite() throws Exception {
		mvc.perform(hr(get(NEW))).andExpect(redirectedUrl(NEW + "/personal"));
		assertThat(page(hr(get(NEW + "/personal")))).contains("<title>Add employee | Kosh</title>",
				"Step 1 of 5: Personal", "aria-current=\"step\"", "Discard draft");

		mvc.perform(hr(personal())).andExpect(redirectedUrl(NEW + "/job"));
		assertThat(page(hr(get(NEW + "/job")))).contains("Step 2 of 5: Job", "T17 Production", "Ravi Kale (",
				"T17 Shiroli factory");
		mvc.perform(hr(job())).andExpect(redirectedUrl(NEW + "/statutory"));
		mvc.perform(hr(statutory())).andExpect(redirectedUrl(NEW + "/bank"));
		mvc.perform(hr(bank())).andExpect(redirectedUrl(NEW + "/review"));

		String review = page(hr(get(NEW + "/review")));
		assertThat(review).contains("Step 5 of 5: Review", "Sunita Ramesh Deshmukh", "9822012345",
				"Flat 4, Shree Apartments, Rajarampuri 5th Lane, Kolhapur, Maharashtra 416008",
				"Same as the current address", "Anil Deshmukh (Brother), 9890011223", "T17 Production",
				"T17 Machine Operator", "T17 Shiroli factory", "On probation", "2 Nov 2026", PAN, "XXXXXX4321",
				"MAHB0000123", "Add employee", "value=\"sunita.d@t17.example.com\"")
			.doesNotContain(ACCOUNT, "Fix 1 thing");

		MvcResult saved = mvc.perform(hr(form(NEW, "createLogin", "true", "_createLogin", "on", "loginEmail",
				"Sunita.Deshmukh@T17.kosh.test")))
			.andExpect(redirectedUrlPattern("/hr/employees/*"))
			.andReturn();

		Employee sunita = employees.findByPan(PAN, hasher).orElseThrow();
		assertThat(saved.getResponse().getRedirectedUrl()).isEqualTo("/hr/employees/" + sunita.getId());
		assertThat(sunita.getCode()).matches("KSH-\\d{4,}");
		assertThat(saved.getFlashMap().get(Flash.ATTRIBUTE)).isEqualTo(
				Flash.success("Sunita Ramesh Deshmukh added as " + sunita.getCode())
					.withText("Sign-in invite sent to sunita.deshmukh@t17.kosh.test. The link works once, for 48 hours."));
		assertThat(sunita.getFirstName()).isEqualTo("Sunita");
		assertThat(sunita.getGender()).isEqualTo(Gender.FEMALE);
		assertThat(sunita.getDateOfBirth()).isEqualTo(LocalDate.of(1993, 2, 11));
		assertThat(sunita.getPersonalEmail()).isEqualTo("sunita.d@t17.example.com");
		assertThat(sunita.getMobile()).isEqualTo("9822012345");
		assertThat(sunita.getCurrentAddress()).isEqualTo(new Address("Flat 4, Shree Apartments", "Rajarampuri 5th Lane",
				"Kolhapur", IndianState.MH, "416008"));
		assertThat(sunita.getPermanentAddress()).isEqualTo(sunita.getCurrentAddress());
		assertThat(sunita.getEmergencyContact()).isEqualTo(new EmergencyContact("Anil Deshmukh", "Brother", "9890011223"));
		assertThat(sunita.getDepartment()).isEqualTo(production);
		assertThat(sunita.getDesignation()).isEqualTo(operator);
		assertThat(sunita.getLocation()).isEqualTo(shiroli);
		assertThat(sunita.getReportingManager()).isEqualTo(ravi);
		assertThat(sunita.getJoiningDate()).isEqualTo(LocalDate.of(2026, 11, 2));
		assertThat(sunita.getEmploymentType()).isEqualTo(EmploymentType.PROBATION);
		assertThat(sunita.getConfirmationDate()).isEqualTo(LocalDate.of(2027, 5, 2));
		assertThat(sunita.getStatus()).isEqualTo(EmployeeStatus.ACTIVE);
		assertThat(sunita.getUan()).isEqualTo("100987654322");
		assertThat(sunita.getPfPolicy()).isEqualTo(PfPolicy.RESTRICT);
		assertThat(sunita.getBankAccount()).isEqualTo(new BankAccount("Bank of Maharashtra", "MAHB0000123", ACCOUNT,
				"Sunita R Deshmukh"));

		User login = sunita.getUser();
		assertThat(login.getEmail()).isEqualTo("sunita.deshmukh@t17.kosh.test");
		assertThat(login.getDisplayName()).isEqualTo("Sunita Deshmukh");
		assertThat(login.getRoles()).containsExactly(Role.EMPLOYEE);
		assertThat(login.getPasswordHash()).isNull();
		verify(mail).send(eq("sunita.deshmukh@t17.kosh.test"), eq("You're invited to Kosh"), contains("/password/set?token="));
		assertThat(session.getAttribute(DRAFT)).isNull();

		AuditEvent added = only(AuditAction.EMPLOYEE_ADDED, sunita.getId());
		assertThat(added.getSummary()).isEqualTo("Added Sunita Ramesh Deshmukh (%s)".formatted(sunita.getCode()));
		assertThat(added.getAfterJson()).contains("\"Name\":\"Sunita Ramesh Deshmukh\"", "\"Department\":\"T17 Production\"",
				"\"PAN\":\"PQXXXXX9K\"", "\"Account number\":\"XXXXXX4321\"",
				"\"Sign-in account\":\"sunita.deshmukh@t17.kosh.test\"")
			.doesNotContain(PAN, ACCOUNT);
		assertThat(events.findAll()).anyMatch(event -> event.getAction() == AuditAction.USER_INVITED
				&& login.getId().equals(event.getEntityId()));
	}

	@Test
	void anEmployeeCanBeAddedWithoutASignInAccountOrBankDetails() throws Exception {
		walkToReview(bankFields("holderName", "", "bankName", "", "ifsc", "", "accountNumber", "",
				"accountNumberConfirm", ""));
		assertThat(page(hr(get(NEW + "/review")))).contains("No bank details. Salary can’t be paid by bank transfer");

		MvcResult saved = mvc.perform(hr(form(NEW, "_createLogin", "on", "loginEmail", "")))
			.andExpect(redirectedUrlPattern("/hr/employees/*"))
			.andReturn();

		Employee sunita = employees.findByPan(PAN, hasher).orElseThrow();
		assertThat(sunita.getUser()).isNull();
		assertThat(sunita.getBankAccount()).isNull();
		assertThat(((Flash) saved.getFlashMap().get(Flash.ATTRIBUTE)).text())
			.isEqualTo("No sign-in account yet. You can create one from Edit at any time.");
		verify(mail, never()).send(any(), any(), any());
	}

	@Test
	void eachStepIsCheckedBeforeTheNextOpensAndKeepsWhatWasTyped() throws Exception {
		start();
		String page = mvc.perform(hr(form(NEW + "/personal", "firstName", " ", "mobile", "12345", "dateOfBirth",
				"11/02/1993", "currentCity", "Kolhapur", "_permanentSame", "on", "permanentSame", "true",
				"emergencyName", "Anil Deshmukh")))
			.andExpect(status().isOk())
			.andExpect(view().name("hr/employees/editor"))
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(page).contains("Enter the first name.", "Choose the gender.", "Enter a real date, like 26 January 2027.",
				"Enter a 10-digit mobile number starting with 6, 7, 8 or 9.", "Enter the address.", "Choose the state.",
				"Enter the PIN code.", "Enter the contact’s mobile number.", "value=\"12345\"", "value=\"Kolhapur\"",
				"value=\"11/02/1993\"", "aria-invalid=\"true\"");
		mvc.perform(hr(get(NEW + "/job"))).andExpect(redirectedUrl(NEW + "/personal"));
		mvc.perform(hr(statutory())).andExpect(redirectedUrl(NEW + "/personal"));
	}

	@Test
	void laterStepsOpenOnlyInOrderAndTheDraftWaitsInTheSession() throws Exception {
		start();
		mvc.perform(hr(get(NEW + "/bank"))).andExpect(redirectedUrl(NEW + "/personal"));
		mvc.perform(hr(get(NEW + "/review"))).andExpect(redirectedUrl(NEW + "/personal"));
		mvc.perform(hr(personal())).andExpect(redirectedUrl(NEW + "/job"));

		// Leave and come back: the draft is picked up at the furthest step reached, with what was typed.
		mvc.perform(hr(get("/hr/employees"))).andExpect(status().isOk());
		mvc.perform(hr(get(NEW)))
			.andExpect(redirectedUrl(NEW + "/job"))
			.andExpect(flash().attribute(Flash.ATTRIBUTE, Flash.info("Continuing the employee you started adding")
				.withText("Your answers so far are kept. Discard the draft to start again.")));
		assertThat(page(hr(get(NEW + "/personal")))).contains("value=\"Sunita\"", "value=\"Flat 4, Shree Apartments\"");
		assertThat(page(hr(get(NEW + "/job")))).contains("href=\"/hr/employees/new/personal\"");
	}

	@Test
	void anAgeThatDoesNotFitTheJoiningDateIsReportedOnTheJoiningDate() throws Exception {
		start();
		mvc.perform(hr(personal()));
		String page = mvc.perform(hr(job("joiningDate", "2000-01-03")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(page).containsPattern("id=\"joiningDate-error\">\\s*<svg[\\s\\S]*?The employee must be at least 14 on the joining date\\.");
	}

	@Test
	void datesAndChoicesFollowTheEmploymentTypeAndTheOrganisation() throws Exception {
		start();
		mvc.perform(hr(personal()));
		asHr();
		org.setActive(OrgKind.DEPARTMENTS, stores.getId(), false);
		String page = mvc.perform(hr(job("employmentType", "PERMANENT", "contractEndDate", "2027-03-31",
				"departmentId", String.valueOf(stores.getId()), "confirmationDate", "2026-10-01", "reportingManagerId",
				"999999")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(page).contains("Only fixed-term employees and interns have a contract end date.",
				"The confirmation date can’t be before the joining date.", "Choose a department from the list.",
				"Choose someone from the list.")
			.doesNotContain("T17 Stores");

		page = mvc.perform(hr(job("employmentType", "FIXED_TERM"))).andExpect(status().isOk()).andReturn()
			.getResponse().getContentAsString();
		assertThat(page).contains("Enter the contract end date for a fixed-term employee.");
	}

	@Test
	void anEmptyOrganisationIsExplainedOnTheJobStep() throws Exception {
		String page = page(hr(get(NEW + "/job")).sessionAttr(DRAFT, reachedJob()));
		assertThat(page).doesNotContain("The organisation isn’t set up yet");
		asHr();
		org.setActive(OrgKind.LOCATIONS, shiroli.getId(), false);
		page = page(hr(get(NEW + "/job")));
		assertThat(page).contains("The organisation isn’t set up yet", "href=\"/hr/org\"");
	}

	@Test
	void duplicatesAreRefusedNextToTheirFields() throws Exception {
		start();
		String page = mvc.perform(hr(personal("personalEmail", "Ravi.Kale@T17.example.com")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		String owner = "Ravi Kale (%s)".formatted(ravi.getCode());
		assertThat(page).contains(owner + " already has this personal email. One person has one employee record.");

		mvc.perform(hr(personal())).andExpect(redirectedUrl(NEW + "/job"));
		mvc.perform(hr(job())).andExpect(redirectedUrl(NEW + "/statutory"));
		page = mvc.perform(hr(statutory("pan", "abcde 1234 f", "uan", "100987654321", "esiIpNumber", "3100456789")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(page).contains(owner + " already has this PAN.", owner + " already has this UAN.",
				owner + " already has this ESI IP number.");

		mvc.perform(hr(statutory())).andExpect(redirectedUrl(NEW + "/bank"));
		mvc.perform(hr(bank())).andExpect(redirectedUrl(NEW + "/review"));
		users.save(new User("taken@t17.kosh.test", "Someone Else", Set.of(Role.EMPLOYEE)));
		page = mvc.perform(hr(form(NEW, "createLogin", "true", "_createLogin", "on", "loginEmail", "TAKEN@t17.kosh.test")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(page).contains("A Kosh user already signs in with this email. Use another one.",
				"value=\"TAKEN@t17.kosh.test\"");
		assertThat(employees.findByPan(PAN, hasher)).isEmpty();
	}

	@Test
	void theTwoAccountNumbersMustMatch() throws Exception {
		start();
		mvc.perform(hr(personal()));
		mvc.perform(hr(job()));
		mvc.perform(hr(statutory()));
		String page = mvc.perform(hr(bank("accountNumberConfirm", "123456784312")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(page).contains("The two account numbers don’t match. Type the number again.");
	}

	@Test
	void problemsOnOtherStepsAreListedOnTheReviewAndBlockSaving() throws Exception {
		walkToReview(bankFields());
		// Someone else takes the PAN meanwhile.
		asHr();
		employees.findById(ravi.getId()).orElseThrow().setPan(PAN, hasher);
		employees.flush();

		String review = page(hr(get(NEW + "/review")));
		assertThat(review).contains("Fix 1 thing before saving", "href=\"/hr/employees/new/statutory\"",
				"already has this PAN.", "Go to Statutory")
			.doesNotContain(">Add employee</button>");
		mvc.perform(hr(form(NEW, "_createLogin", "on"))).andExpect(status().isOk());
		assertThat(employees.findAll()).noneMatch(employee -> "Sunita".equals(employee.getFirstName()));
	}

	@Test
	void aFailedInviteEmailAddsNobody() throws Exception {
		walkToReview(bankFields());
		doThrow(new MailSendException("SMTP down")).when(mail).send(any(), any(), any());

		String page = mvc.perform(hr(form(NEW, "createLogin", "true", "_createLogin", "on", "loginEmail",
				"sunita@t17.kosh.test")))
			.andExpect(status().isOk())
			.andExpect(view().name("hr/employees/editor"))
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(page).contains("We couldn’t send the invite email, so the employee wasn’t added.");
		assertThat(TestTransaction.isFlaggedForRollback()).isTrue();
		assertThat(session.getAttribute(DRAFT)).isNotNull();
	}

	@Test
	void discardingThrowsTheDraftAway() throws Exception {
		start();
		mvc.perform(hr(personal()));
		mvc.perform(hr(form(NEW + "/discard")))
			.andExpect(redirectedUrl("/hr/employees"))
			.andExpect(flash().attribute(Flash.ATTRIBUTE, Flash.info("Draft discarded").withText("Nothing was saved.")));
		assertThat(session.getAttribute(DRAFT)).isNull();
		mvc.perform(hr(get(NEW + "/job"))).andExpect(redirectedUrl(NEW));
	}

	@Test
	void aFormThatClosedSavesNothingAndSaysSo() throws Exception {
		mvc.perform(hr(personal()))
			.andExpect(redirectedUrl(NEW))
			.andExpect(flash().attribute(Flash.ATTRIBUTE, Flash.warning("That form had closed, so nothing was saved")
				.withText("Forms are kept until they are saved or you sign out. Start again here.")));
		mvc.perform(hr(form(NEW))).andExpect(redirectedUrl(NEW));
	}

	@Test
	void theDirectoryAndProfileLeadToTheForm() throws Exception {
		assertThat(page(hr(get("/hr/employees")), "hr/employees/list")).contains("href=\"/hr/employees/new\"",
				"Add employee");
		String id = String.valueOf(ravi.getId());
		assertThat(page(hr(get("/hr/employees/" + id)), "hr/employees/profile"))
			.contains("/hr/employees/" + id + "/edit?step=personal");
		assertThat(page(hr(get("/hr/employees/" + id + "/job")), "hr/employees/profile"))
			.contains("/hr/employees/" + id + "/edit?step=job");
		assertThat(page(hr(get("/hr/employees/" + id + "/statutory")), "hr/employees/profile"))
			.contains("/hr/employees/" + id + "/edit?step=statutory");
		assertThat(page(hr(get("/hr/employees/" + id + "/bank")), "hr/employees/profile"))
			.contains("/hr/employees/" + id + "/edit?step=bank");
	}

	// ------------------------------------------------------------------ editing

	@Test
	void editingStartsFromTheRecordAtTheStepAskedFor() throws Exception {
		String base = "/hr/employees/" + ravi.getId() + "/edit";
		mvc.perform(hr(get(base).param("step", "statutory"))).andExpect(redirectedUrl(base + "/statutory"));
		String page = page(hr(get(base + "/statutory")));
		assertThat(page).contains("<title>Edit Ravi Kale | Kosh</title>", ravi.getCode(), "value=\"ABCDE1234F\"",
				"value=\"100987654321\"").doesNotContain("Discard draft", "View in audit log");

		page = page(hr(get(base + "/bank")));
		assertThat(page).contains("On file: XXXXXX0001. Leave empty to keep it", "value=\"MAHB0000123\"")
			.doesNotContain("998877660001");
		// Every step is open at once, and an unknown step is not a page.
		assertThat(page(hr(get(base + "/review")))).contains("Save changes", "XXXXXX0001");
		mvc.perform(hr(get(base + "/salary"))).andExpect(status().isNotFound());
		mvc.perform(hr(get("/hr/employees/999999/edit"))).andExpect(status().isNotFound());
	}

	@Test
	void aBankChangeIsSavedAndAuditedOnItsOwnWithMaskedNumbers() throws Exception {
		String base = "/hr/employees/" + ravi.getId() + "/edit";
		mvc.perform(hr(get(base).param("step", "bank")));
		mvc.perform(hr(form(base + "/bank", "holderName", "Ravi Kale", "bankName", "State Bank of India", "ifsc",
				"SBIN0001234", "accountNumber", "556677885544", "accountNumberConfirm", "556677885544")))
			.andExpect(redirectedUrl(base + "/review"));
		assertThat(page(hr(get(base + "/review")))).contains("XXXXXX5544").doesNotContain("556677885544");

		mvc.perform(hr(form(base, "_createLogin", "on")))
			.andExpect(redirectedUrl("/hr/employees/" + ravi.getId()))
			.andExpect(flash().attribute(Flash.ATTRIBUTE,
					Flash.success("Saved Ravi Kale").withText("Changed: Bank, IFSC, Account number.")));

		Employee saved = employees.findById(ravi.getId()).orElseThrow();
		assertThat(saved.getBankAccount()).isEqualTo(new BankAccount("State Bank of India", "SBIN0001234", "556677885544",
				"Ravi Kale"));
		AuditEvent bank = only(AuditAction.BANK_DETAILS_CHANGED, ravi.getId());
		assertThat(bank.getSummary()).isEqualTo("Changed the salary account of Ravi Kale (%s)".formatted(ravi.getCode()));
		assertThat(bank.getBeforeJson()).contains("XXXXXX0001", "Bank of Maharashtra").doesNotContain("998877660001");
		assertThat(bank.getAfterJson()).contains("XXXXXX5544", "SBIN0001234").doesNotContain("556677885544");
		assertThat(events(AuditAction.EMPLOYEE_UPDATED, ravi.getId())).isEmpty();
	}

	@Test
	void hrSeesAndChangesThePanInFullAndTheAuditKeepsOnlyMasks() throws Exception {
		String base = "/hr/employees/" + ravi.getId() + "/edit";
		mvc.perform(hr(get(base).param("step", "personal")));
		mvc.perform(hr(form(base + "/personal", personalFields("firstName", "Ravi", "middleName", "", "lastName", "Kale",
				"gender", "MALE", "dateOfBirth", "1990-06-15", "personalEmail", "ravi.kale@t17.example.com", "mobile",
				"9890012345", "currentLine1", "12 Shahupuri", "currentLine2", "", "currentCity", "Kolhapur",
				"currentPincode", "416001", "emergencyName", "", "emergencyRelation", "", "emergencyPhone", ""))))
			.andExpect(redirectedUrl(base + "/job"));
		mvc.perform(hr(form(base + "/statutory", "pan", "ABCDE1234G", "uan", "100987654321", "esiIpNumber",
				"3100456789", "pfPolicy", "RESTRICT", "_ptExempt", "on", "_personWithDisability", "on")))
			.andExpect(redirectedUrl(base + "/bank"));
		mvc.perform(hr(form(base, "_createLogin", "on"))).andExpect(redirectedUrl("/hr/employees/" + ravi.getId()));

		Employee saved = employees.findById(ravi.getId()).orElseThrow();
		assertThat(saved.getPan()).isEqualTo("ABCDE1234G");
		assertThat(saved.getMobile()).isEqualTo("9890012345");
		assertThat(saved.getCurrentAddress().city()).isEqualTo("Kolhapur");
		AuditEvent changed = only(AuditAction.EMPLOYEE_UPDATED, ravi.getId());
		assertThat(changed.getSummary()).isEqualTo("Changed Ravi Kale (%s): Mobile, PAN".formatted(ravi.getCode()));
		assertThat(changed.getBeforeJson()).isEqualTo("{\"Mobile\":\"9822012345\",\"PAN\":\"ABXXXXX4F\"}");
		assertThat(changed.getAfterJson()).isEqualTo("{\"Mobile\":\"9890012345\",\"PAN\":\"ABXXXXX4G\"}");
		assertThat(events(AuditAction.BANK_DETAILS_CHANGED, ravi.getId())).isEmpty();
	}

	@Test
	void anAdminSeesOnlyTheMaskedPanAndKeepsItByLeavingTheFieldEmpty() throws Exception {
		String base = "/hr/employees/" + ravi.getId() + "/edit";
		mvc.perform(admin(get(base).param("step", "statutory")));
		String page = page(admin(get(base + "/statutory")));
		assertThat(page).contains("On file: ABXXXXX4F. Leave empty to keep it").doesNotContain("ABCDE1234F");
		mvc.perform(admin(form(base + "/statutory", "pan", "", "uan", "100987654321", "esiIpNumber", "3100456789",
				"pfPolicy", "ACTUAL", "_ptExempt", "on", "_personWithDisability", "on")))
			.andExpect(redirectedUrl(base + "/bank"));
		assertThat(page(admin(get(base + "/review")))).contains("ABXXXXX4F").doesNotContain("ABCDE1234F");
		mvc.perform(admin(form(base, "_createLogin", "on")))
			.andExpect(flash().attribute(Flash.ATTRIBUTE, Flash.success("Saved Ravi Kale").withText("Changed: PF policy.")));

		Employee saved = employees.findById(ravi.getId()).orElseThrow();
		assertThat(saved.getPan()).isEqualTo("ABCDE1234F");
		assertThat(saved.getPfPolicy()).isEqualTo(PfPolicy.ACTUAL);
	}

	@Test
	void savingAnUnchangedRecordSavesNothing() throws Exception {
		String base = "/hr/employees/" + ravi.getId() + "/edit";
		mvc.perform(hr(get(base)));
		mvc.perform(hr(form(base, "_createLogin", "on")))
			.andExpect(flash().attribute(Flash.ATTRIBUTE, Flash.info("Nothing to save").withText("Ravi Kale’s record is as it was.")));
		assertThat(events(AuditAction.EMPLOYEE_UPDATED, ravi.getId())).isEmpty();
		assertThat(events(AuditAction.BANK_DETAILS_CHANGED, ravi.getId())).isEmpty();
	}

	@Test
	void anEditGivesASignInAccountToSomeoneWithout() throws Exception {
		String base = "/hr/employees/" + ravi.getId() + "/edit";
		mvc.perform(hr(get(base)));
		mvc.perform(hr(form(base, "createLogin", "true", "_createLogin", "on", "loginEmail", "ravi.kale@t17.kosh.test")))
			.andExpect(flash().attribute(Flash.ATTRIBUTE, Flash.success("Saved Ravi Kale").withText(
					"Changed: Sign-in account. Sign-in invite sent to ravi.kale@t17.kosh.test. The link works once, for 48 hours.")));
		assertThat(employees.findById(ravi.getId()).orElseThrow().getUser().getEmail()).isEqualTo("ravi.kale@t17.kosh.test");
		verify(mail).send(eq("ravi.kale@t17.kosh.test"), eq("You're invited to Kosh"), any());
		String review = page(hr(get(base + "/review")).sessionAttr("employee-draft:" + ravi.getId(),
				draftFor(ravi.getId())));
		assertThat(review).contains("Signs in to Kosh as", "ravi.kale@t17.kosh.test").doesNotContain("name=\"loginEmail\"");
	}

	@Test
	void aRecordChangedBysomeoneElseMeanwhileIsNotOverwritten() throws Exception {
		String base = "/hr/employees/" + ravi.getId() + "/edit";
		mvc.perform(hr(get(base)));
		asHr();
		employees.findById(ravi.getId()).orElseThrow().setMobile("9000000001");
		employees.flush();

		mvc.perform(hr(form(base, "_createLogin", "on")))
			.andExpect(redirectedUrl("/hr/employees/" + ravi.getId()))
			.andExpect(flash().attribute(Flash.ATTRIBUTE, Flash.danger("Your changes weren’t saved").withText(
					"Someone else saved changes to this record while you were editing it. This is the record as it is now: edit it again to make yours.")));
		assertThat(session.getAttribute("employee-draft:" + ravi.getId())).isNull();
	}

	@Test
	void aReportingLineNeverLoops() throws Exception {
		Employee sunita = hire("Sunita", "Deshmukh");
		sunita.setReportingManager(ravi);
		employees.flush();
		String base = "/hr/employees/" + ravi.getId() + "/edit";
		mvc.perform(hr(get(base).param("step", "job")));
		String page = page(hr(get(base + "/job")));
		assertThat(page).contains("Sunita Deshmukh (" + sunita.getCode() + ")").doesNotContain("Ravi Kale (" + ravi.getCode() + ")");

		page = mvc.perform(hr(form(base + "/job", jobFields("reportingManagerId", String.valueOf(sunita.getId()),
				"joiningDate", "2024-04-01", "employmentType", "PERMANENT", "confirmationDate", ""))))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(page).contains("Choose someone else: Sunita Deshmukh already reports to Ravi Kale.");
	}

	@Test
	void anInactiveItemStaysChoosableForTheEmployeeWhoHasIt() throws Exception {
		Employee meena = hire("Meena", "Jadhav");
		meena.setDepartment(stores);
		employees.flush();
		org.setActive(OrgKind.DEPARTMENTS, stores.getId(), false);
		String base = "/hr/employees/" + meena.getId() + "/edit";
		mvc.perform(hr(get(base).param("step", "job")));
		assertThat(page(hr(get(base + "/job")))).contains("T17 Stores (inactive)");
		assertThat(page(hr(get(base + "/review")))).contains("T17 Stores (inactive)", "Save changes");
	}

	// ------------------------------------------------------------------ who may

	@ParameterizedTest
	@ValueSource(strings = { "PAYROLL", "FINANCE", "MANAGER", "EMPLOYEE" })
	void onlyHrAndAdminMayAddOrEditEmployees(String role) throws Exception {
		SecurityContextHolder.clearContext();
		String edit = "/hr/employees/" + ravi.getId() + "/edit";
		for (MockHttpServletRequestBuilder request : List.of(get(NEW), get(NEW + "/personal"), personal(),
				form(NEW), form(NEW + "/discard"), get(edit), get(edit + "/bank"), form(edit + "/bank"), form(edit))) {
			mvc.perform(request.with(user("someone@t17.kosh.test").roles(role, "EMPLOYEE")).session(session))
				.andExpect(status().isForbidden());
		}
		assertThat(session.getAttribute(DRAFT)).isNull();
	}

	@Test
	void anonymousVisitorsAreSentToSignIn() throws Exception {
		SecurityContextHolder.clearContext();
		mvc.perform(get(NEW)).andExpect(redirectedUrl("/login"));
		mvc.perform(get("/hr/employees/" + ravi.getId() + "/edit")).andExpect(redirectedUrl("/login"));
	}

	@Test
	void aFormWithoutItsCsrfTokenSavesNothing() throws Exception {
		walkToReview(bankFields());
		mvc.perform(post(NEW).param("_createLogin", "on").with(user(HR).roles("HR", "EMPLOYEE")).session(session))
			.andExpect(status().is3xxRedirection());
		assertThat(employees.findByPan(PAN, hasher)).isEmpty();
	}

	@Test
	void theServicesRefuseEveryoneButHrAndAdmin() {
		SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("p", null, "ROLE_PAYROLL"));
		assertThatThrownBy(service::newDraft).isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> service.draftFor(ravi.getId(), viewer("ROLE_PAYROLL"))).isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> service.create(new EmployeeForm(), viewer("ROLE_PAYROLL"))).isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> accounts.inviteEmployee("Sunita", "s@t17.kosh.test", viewer("ROLE_PAYROLL")))
			.isInstanceOf(AccessDeniedException.class);

		asHr();
		User invited = accounts.inviteEmployee("Sunita Deshmukh", "sunita@t17.kosh.test", viewer("ROLE_HR"));
		assertThat(invited.getRoles()).containsExactly(Role.EMPLOYEE);
		assertThatThrownBy(() -> accounts.invite("Sunita", "s2@t17.kosh.test", Set.of(Role.EMPLOYEE), viewer("ROLE_HR")))
			.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	void theServiceRefusesAnInvalidDraftWhoeverCallsIt() {
		EmployeeForm form = service.newDraft();
		form.setFirstName("Sunita");
		assertThatThrownBy(() -> service.create(form, viewer("ROLE_HR")))
			.isInstanceOfSatisfying(com.kosh.common.BusinessRuleException.class,
					refused -> assertThat(refused.getField()).isEqualTo("gender"));
		assertThat(service.problems(form, EmployeeStep.PERSONAL)).extracting(EmployeeService.Problem::field)
			.containsExactly("gender", "dateOfBirth", "mobile");
		assertThat(service.problems(form, null)).extracting(EmployeeService.Problem::field)
			.contains("departmentId", "designationId", "locationId", "joiningDate", "employmentType", "loginEmail");
	}

	// ------------------------------------------------------------------ helpers

	private void start() throws Exception {
		mvc.perform(hr(get(NEW))).andExpect(redirectedUrl(NEW + "/personal"));
	}

	private void walkToReview(String[] bank) throws Exception {
		start();
		mvc.perform(hr(personal())).andExpect(redirectedUrl(NEW + "/job"));
		mvc.perform(hr(job())).andExpect(redirectedUrl(NEW + "/statutory"));
		mvc.perform(hr(statutory())).andExpect(redirectedUrl(NEW + "/bank"));
		mvc.perform(hr(form(NEW + "/bank", bank))).andExpect(redirectedUrl(NEW + "/review"));
	}

	private EmployeeForm reachedJob() {
		EmployeeForm form = service.newDraft();
		form.reach(EmployeeStep.JOB);
		return form;
	}

	private EmployeeForm draftFor(long id) {
		asHr();
		return service.draftFor(id, viewer("ROLE_HR"));
	}

	private MockHttpServletRequestBuilder personal(String... overrides) {
		return form(NEW + "/personal", personalFields(overrides));
	}

	private MockHttpServletRequestBuilder job(String... overrides) {
		return form(NEW + "/job", jobFields(overrides));
	}

	private MockHttpServletRequestBuilder statutory(String... overrides) {
		return form(NEW + "/statutory", with(new String[] { "pan", PAN.toLowerCase(), "uan", "100987654322",
				"esiIpNumber", "", "pfPolicy", "RESTRICT", "_ptExempt", "on", "_personWithDisability", "on" }, overrides));
	}

	private MockHttpServletRequestBuilder bank(String... overrides) {
		return form(NEW + "/bank", bankFields(overrides));
	}

	private static String[] personalFields(String... overrides) {
		return with(new String[] { "firstName", " Sunita ", "middleName", "Ramesh", "lastName", "Deshmukh", "gender",
				"FEMALE", "dateOfBirth", "1993-02-11", "personalEmail", "Sunita.D@T17.example.com", "mobile",
				"98220 12345", "currentLine1", "Flat 4,  Shree Apartments", "currentLine2", "Rajarampuri 5th Lane",
				"currentCity", "Kolhapur", "currentState", "MH", "currentPincode", "416 008", "_permanentSame", "on",
				"permanentSame", "true", "emergencyName", "Anil Deshmukh", "emergencyRelation", "Brother",
				"emergencyPhone", "9890011223" }, overrides);
	}

	private String[] jobFields(String... overrides) {
		return with(new String[] { "departmentId", String.valueOf(production.getId()), "designationId",
				String.valueOf(operator.getId()), "locationId", String.valueOf(shiroli.getId()), "reportingManagerId",
				String.valueOf(ravi.getId()), "gradeId", "", "costCentreId", "", "joiningDate", "2026-11-02",
				"employmentType", "PROBATION", "contractEndDate", "", "confirmationDate", "2027-05-02" }, overrides);
	}

	private static String[] bankFields(String... overrides) {
		return with(new String[] { "holderName", "Sunita R Deshmukh", "bankName", "Bank of Maharashtra", "ifsc",
				"mahb0000123", "accountNumber", "1234 5678 4321", "accountNumberConfirm", ACCOUNT }, overrides);
	}

	/** Name-value pairs with some values replaced (or added). */
	private static String[] with(String[] pairs, String... overrides) {
		List<String> result = new ArrayList<>(List.of(pairs));
		for (int i = 0; i < overrides.length; i += 2) {
			int at = -1;
			for (int j = 0; j < result.size(); j += 2) {
				if (result.get(j).equals(overrides[i])) {
					at = j;
				}
			}
			if (at >= 0) {
				result.set(at + 1, overrides[i + 1]);
			}
			else {
				result.add(overrides[i]);
				result.add(overrides[i + 1]);
			}
		}
		return result.toArray(String[]::new);
	}

	private static MockHttpServletRequestBuilder form(String path, String... pairs) {
		MockHttpServletRequestBuilder request = post(path).with(csrf());
		for (int i = 0; i < pairs.length; i += 2) {
			request.param(pairs[i], pairs[i + 1]);
		}
		return request;
	}

	private MockHttpServletRequestBuilder hr(MockHttpServletRequestBuilder request) {
		return request.with(user(HR).roles("HR", "EMPLOYEE")).session(session);
	}

	private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request) {
		return request.with(user(ADMIN).roles("ADMIN", "EMPLOYEE")).session(session);
	}

	private String page(MockHttpServletRequestBuilder request) throws Exception {
		return page(request, "hr/employees/editor");
	}

	private String page(MockHttpServletRequestBuilder request, String view) throws Exception {
		return mvc.perform(request).andExpect(status().isOk()).andExpect(view().name(view)).andReturn().getResponse()
			.getContentAsString();
	}

	private Employee hire(String firstName, String lastName) {
		asHr();
		Employee employee = new Employee(employees.nextCode());
		employee.setFirstName(firstName);
		employee.setLastName(lastName);
		employee.setGender(Gender.MALE);
		employee.setDateOfBirth(LocalDate.of(1990, 6, 15));
		employee.setMobile("9822012345");
		employee.setCurrentAddress(new Address("12 Shahupuri", null, "Kolhapur", IndianState.MH, "416001"));
		employee.setPermanentAddress(employee.getCurrentAddress());
		employee.setJoiningDate(LocalDate.of(2024, 4, 1));
		employee.setEmploymentType(EmploymentType.PERMANENT, null);
		employee.setDepartment(production);
		employee.setDesignation(operator);
		employee.setLocation(shiroli);
		employee.setPfPolicy(PfPolicy.RESTRICT);
		return employees.save(employee);
	}

	private List<AuditEvent> events(AuditAction action, long id) {
		return events.findAll()
			.stream()
			.filter(event -> event.getAction() == action && Employee.AUDIT_TYPE.equals(event.getEntityType())
					&& Long.valueOf(id).equals(event.getEntityId()))
			.toList();
	}

	private AuditEvent only(AuditAction action, long id) {
		List<AuditEvent> found = events(action, id);
		assertThat(found).hasSize(1);
		return found.getFirst();
	}

	private static Authentication viewer(String authority) {
		return new TestingAuthenticationToken(HR, null, authority);
	}

	private static void asHr() {
		SecurityContextHolder.getContext().setAuthentication(viewer("ROLE_HR"));
	}

}
