package com.kosh.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import jakarta.persistence.EntityManager;

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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.kosh.common.ForbiddenException;
import com.kosh.common.Gender;
import com.kosh.common.HmacHasher;
import com.kosh.common.IndianState;
import com.kosh.employee.EmployeeDirectoryService.Event;
import com.kosh.employee.EmployeeDirectoryService.Profile;
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
 * The employee directory and profile (PLAN T16): search, filters, status chips, sort, pagination and total count on
 * {@code /hr/employees}; the profile tabs, with the Salary tab only for the salary roles, the PAN in full only for HR
 * and the bank account always masked; empty states, 404s and access by role. Each test runs in a rolled-back
 * transaction against an otherwise empty employees table.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class EmployeeDirectoryTest {

	private static final String HR = "hr@t16.kosh.test";

	private static final String PAN = "ABCDE1234F";

	private static final String ACCOUNT = "123456784321";

	private final MockMvc mvc;

	private final EmployeeRepository employees;

	private final EmployeeDirectoryService directory;

	private final OrgService org;

	private final UserRepository users;

	private final HmacHasher hasher;

	private final EntityManager em;

	private Department production;

	private Department assembly;

	private Designation operator;

	private WorkLocation shiroli;

	private WorkLocation pune;

	@Autowired
	EmployeeDirectoryTest(MockMvc mvc, EmployeeRepository employees, EmployeeDirectoryService directory,
			OrgService org, UserRepository users, HmacHasher hasher, EntityManager em) {
		this.mvc = mvc;
		this.employees = employees;
		this.directory = directory;
		this.org = org;
		this.users = users;
		this.hasher = hasher;
		this.em = em;
	}

	@BeforeEach
	void organisation() {
		asHr();
		production = em.find(Department.class, org.create(OrgKind.DEPARTMENTS, OrgDetails.of("T16-PROD", "T16 Production")));
		assembly = em.find(Department.class, org.create(OrgKind.DEPARTMENTS, OrgDetails.of("T16-ASM", "T16 Assembly")));
		operator = em.find(Designation.class, org.create(OrgKind.DESIGNATIONS, OrgDetails.of("T16-OPR", "T16 Machine Operator")));
		shiroli = location("T16-SHR", "T16 Shiroli factory");
		pune = location("T16-PUN", "T16 Pune sales office");
	}

	@AfterEach
	void signOut() {
		SecurityContextHolder.clearContext();
	}

	// ------------------------------------------------------------------ directory

	@Test
	void theDirectoryListsEveryoneWithTheTotalAndACountPerStatus() throws Exception {
		Employee manager = hire("Ravi", "Kale", production, shiroli);
		Employee sunita = hire("Sunita", "Deshmukh", production, shiroli);
		sunita.setReportingManager(manager);
		Employee anil = hire("Anil", "Patil", assembly, pune);
		anil.setStatus(EmployeeStatus.ON_NOTICE);
		Employee meena = hire("Meena", "Jadhav", assembly, pune);
		meena.setStatus(EmployeeStatus.EXITED);
		meena.setEmploymentType(EmploymentType.FIXED_TERM, LocalDate.of(2026, 3, 31));
		em.flush();

		String page = page(hr("/hr/employees"));

		assertThat(page).contains("<title>Employees | Kosh</title>")
			.contains("<a class=\"cell-title\" href=\"/hr/employees/" + sunita.getId() + "\">Sunita Deshmukh</a>")
			.contains("<td class=\"mono\">" + sunita.getCode() + "</td>")
			.contains("<span class=\"cell-sub\">T16 Machine Operator</span>")
			.contains("<td>T16 Production</td>")
			.contains("<td>T16 Pune sales office</td>")
			.contains("<td>Ravi Kale</td>")
			.contains("<td>Fixed term</td>")
			.contains("<td>1 Apr 2024</td>")
			.contains("<span class=\"badge\" data-status=\"ON_NOTICE\">On notice</span>")
			.contains("<span class=\"badge\" data-status=\"EXITED\">Exited</span>")
			.contains("<caption class=\"visually-hidden\">Employees</caption>")
			.containsPattern("<p class=\"result-count\"><strong>4</strong>\\s*employees</p>")
			.contains("of <strong>4</strong> <span>employees</span>");
		// Status chips: All is current; each chip counts its status.
		assertThat(page).contains("<nav class=\"cluster\" aria-label=\"Filter by status\">")
			.contains("<a class=\"chip\" href=\"/hr/employees\" aria-current=\"true\">All <span class=\"chip-count\">4</span></a>")
			.containsPattern("<a class=\"chip\" href=\"/hr/employees\\?status=ACTIVE\">\\s*Active <span class=\"chip-count\">2</span></a>")
			.containsPattern("href=\"/hr/employees\\?status=ON_NOTICE\">\\s*On notice <span class=\"chip-count\">1</span>")
			.containsPattern("href=\"/hr/employees\\?status=EXITED\">\\s*Exited <span class=\"chip-count\">1</span>");
		// Sorted by name: Anil, Meena, Ravi, Sunita.
		assertThat(order(page, "Anil Patil", "Meena Jadhav", "Ravi Kale", "Sunita Deshmukh")).isTrue();
		assertThat(page).contains("<th scope=\"col\" aria-sort=\"ascending\">")
			.contains("<option value=\"" + production.getId() + "\">T16 Production</option>")
			.contains("<option value=\"" + shiroli.getId() + "\">T16 Shiroli factory</option>")
			.contains("<option value=\"INTERN\">Intern</option>")
			.doesNotContain("No employees");
	}

	@Test
	void searchMatchesEveryWordAgainstNamesAndTheCode() throws Exception {
		Employee sunita = hire("Sunita", "Deshmukh", production, shiroli);
		sunita.setMiddleName("Ramesh");
		Employee anil = hire("Anil", "Deshpande", assembly, pune);
		em.flush();

		assertThat(page(hr("/hr/employees").queryParam("q", "  sunita DESH ")))
			.contains("Sunita Ramesh Deshmukh")
			.doesNotContain("Anil Deshpande")
			.contains("value=\"sunita DESH\"");
		assertThat(page(hr("/hr/employees").queryParam("q", "ramesh"))).contains("Sunita Ramesh Deshmukh");
		assertThat(page(hr("/hr/employees").queryParam("q", "desh"))).contains("Sunita Ramesh Deshmukh", "Anil Deshpande");
		assertThat(page(hr("/hr/employees").queryParam("q", anil.getCode().toLowerCase())))
			.contains("Anil Deshpande")
			.doesNotContain("Sunita Ramesh Deshmukh");
		String number = anil.getCode().substring(Employee.CODE_PREFIX.length());
		assertThat(page(hr("/hr/employees").queryParam("q", number))).contains("Anil Deshpande");
		// LIKE wildcards are plain text.
		assertThat(page(hr("/hr/employees").queryParam("q", "%")))
			.contains("No employees match these filters")
			.contains("<a class=\"btn btn-secondary\" href=\"/hr/employees\">Clear filters</a>");
		// A hand-built query past the search box's 100 characters is cut there, not run word by word in full.
		assertThat(page(hr("/hr/employees").queryParam("q", "desh ".repeat(1000))))
			.contains("Sunita Ramesh Deshmukh", "Anil Deshpande")
			.contains("value=\"" + "desh ".repeat(20).strip() + "\"");
		assertThat(new EmployeeFilter("x".repeat(500), null, null, null, null).text()).hasSize(EmployeeFilter.MAX_TEXT);
	}

	@Test
	void filtersNarrowTheListAndTheChipsCountWithinThem() throws Exception {
		hire("Ravi", "Kale", production, shiroli);
		Employee intern = hire("Kiran", "More", production, shiroli);
		intern.setEmploymentType(EmploymentType.INTERN, null);
		Employee anil = hire("Anil", "Patil", assembly, pune);
		anil.setStatus(EmployeeStatus.ON_NOTICE);
		hire("Meena", "Jadhav", assembly, pune);
		em.flush();

		String byDepartment = page(hr("/hr/employees").queryParam("department", String.valueOf(assembly.getId())));
		assertThat(byDepartment).contains("Anil Patil", "Meena Jadhav")
			.doesNotContain("Ravi Kale")
			.containsPattern("<option value=\"" + assembly.getId() + "\"\\s+selected=\"selected\">T16 Assembly</option>")
			.contains(">All <span class=\"chip-count\">2</span>")
			.containsPattern("Active <span class=\"chip-count\">1</span>")
			.containsPattern("On notice <span class=\"chip-count\">1</span>")
			.containsPattern("Exited <span class=\"chip-count\">0</span>")
			// A chip keeps the other filters and starts again on page 1.
			.contains("href=\"/hr/employees?department=" + assembly.getId() + "&amp;status=ON_NOTICE\"")
			.contains("<a class=\"btn btn-ghost\" href=\"/hr/employees\">Clear</a>");

		String byStatus = page(hr("/hr/employees").queryParam("department", String.valueOf(assembly.getId()))
			.queryParam("status", "ON_NOTICE")
			.queryParam("page", "0"));
		assertThat(byStatus).contains("Anil Patil")
			.doesNotContain("Meena Jadhav")
			.containsPattern("aria-current=\"true\">\\s*On notice")
			.contains("<input type=\"hidden\" name=\"status\" value=\"ON_NOTICE\">")
			// All drops the status, keeps the department and is no longer current.
			.contains("<a class=\"chip\" href=\"/hr/employees?department=" + assembly.getId() + "\">All")
			.doesNotContain("href=\"/hr/employees?department=" + assembly.getId() + "&amp;status=ON_NOTICE&amp;page");

		assertThat(page(hr("/hr/employees").queryParam("location", String.valueOf(shiroli.getId()))))
			.contains("Ravi Kale", "Kiran More")
			.doesNotContain("Anil Patil");
		assertThat(page(hr("/hr/employees").queryParam("type", "INTERN")))
			.contains("Kiran More")
			.doesNotContain("Ravi Kale")
			.contains("<option value=\"INTERN\" selected=\"selected\">Intern</option>");
	}

	@ParameterizedTest
	@ValueSource(strings = { "department=abc", "location=12.5", "status=FIRED", "type=permanent", "q=%20%20" })
	void unreadableFiltersAreIgnored(String query) throws Exception {
		hire("Ravi", "Kale", production, shiroli);
		em.flush();

		String[] pair = query.split("=");
		// "All" is current; its link drops only the status.
		String all = pair[0].equals("status") ? "/hr/employees" : "/hr/employees?" + query;
		assertThat(page(hr("/hr/employees").queryParam(pair[0], pair[1].replace("%20", " "))))
			.contains("Ravi Kale")
			.contains("<a class=\"chip\" href=\"" + all + "\" aria-current=\"true\">All")
			.doesNotContain("Clear</a>");
	}

	@Test
	void columnsSortBothWaysAndUnknownSortsFallBackToTheName() throws Exception {
		Employee early = hire("Zubin", "Shaikh", production, shiroli);
		early.setJoiningDate(LocalDate.of(2019, 7, 1));
		Employee late = hire("Aarti", "Pawar", production, shiroli);
		late.setJoiningDate(LocalDate.of(2026, 9, 1));
		hire("Mohan", "Gaikwad", assembly, pune);
		em.flush();

		String byJoining = page(hr("/hr/employees").queryParam("sort", "joiningDate,desc"));
		assertThat(order(byJoining, "Aarti Pawar", "Mohan Gaikwad", "Zubin Shaikh")).isTrue();
		assertThat(byJoining).contains("<th scope=\"col\" aria-sort=\"descending\">")
			.contains("<a class=\"sort\" href=\"/hr/employees?sort=joiningDate,asc\">")
			// The filter form keeps the chosen sort.
			.contains("<input type=\"hidden\" name=\"sort\" value=\"joiningDate,desc\">");

		assertThat(order(page(hr("/hr/employees").queryParam("sort", "department.name,asc")), "Mohan Gaikwad",
				"Zubin Shaikh"))
			.isTrue();
		assertThat(order(page(hr("/hr/employees").queryParam("sort", "code,desc")), "Mohan Gaikwad", "Aarti Pawar",
				"Zubin Shaikh"))
			.isTrue();
		String unknown = page(hr("/hr/employees").queryParam("sort", "pan,asc"));
		assertThat(order(unknown, "Aarti Pawar", "Mohan Gaikwad", "Zubin Shaikh")).isTrue();
		assertThat(unknown).doesNotContain("name=\"sort\"");

		// Typed names sort case-insensitively; codes and dates sort as stored, so their indexes can serve the sort.
		mvc.perform(hr("/hr/employees")).andExpect(model().attribute("sort", Sort.by(Sort.Order.asc("firstName").ignoreCase())));
		mvc.perform(hr("/hr/employees").queryParam("sort", "department.name,desc"))
			.andExpect(model().attribute("sort", Sort.by(Sort.Order.desc("department.name").ignoreCase())));
		mvc.perform(hr("/hr/employees").queryParam("sort", "code,desc"))
			.andExpect(model().attribute("sort", Sort.by(Sort.Order.desc("code"))));
		mvc.perform(hr("/hr/employees").queryParam("sort", "joiningDate,asc"))
			.andExpect(model().attribute("sort", Sort.by(Sort.Order.asc("joiningDate"))));
	}

	@Test
	void theListPagesTwentyFiveAtATimeAndAPagePastTheEndShowsTheLastOne() throws Exception {
		for (int n = 1; n <= 26; n++) {
			hire("Worker", "A%02d".formatted(n), production, shiroli);
		}
		em.flush();

		String first = page(hr("/hr/employees"));
		assertThat(first).contains("Worker A25")
			.doesNotContain("Worker A26")
			.contains("Showing <strong>1–25</strong>")
			.contains("of <strong>26</strong> <span>employees</span>")
			.contains("<a class=\"page-link\" href=\"/hr/employees?page=1\" rel=\"next\">");

		assertThat(page(hr("/hr/employees").queryParam("page", "1"))).contains("Worker A26")
			.doesNotContain("Worker A25")
			.contains("Showing <strong>26–26</strong>");
		assertThat(page(hr("/hr/employees").queryParam("page", "9"))).contains("Worker A26")
			.contains("Showing <strong>26–26</strong>");
	}

	@Test
	void anEmptyDirectorySaysSo() throws Exception {
		String page = page(hr("/hr/employees"));

		assertThat(page).contains("<h2 class=\"empty-title\">No employees yet</h2>")
			.contains("<img src=\"/img/empty/people.svg\"")
			.contains("Add the first employee")
			.contains(">All <span class=\"chip-count\">0</span>")
			.doesNotContain("<table")
			.doesNotContain("class=\"pagination\"");
	}

	@Test
	void theTopBarSearchSendsItsQueryHere() throws Exception {
		hire("Sunita", "Deshmukh", production, shiroli);
		em.flush();

		assertThat(page(hr("/hr/employees").queryParam("q", "Sunita")))
			.containsPattern("role=\"search\" method=\"get\"\\s+action=\"/hr/employees\"")
			.contains("<a class=\"nav-link\" href=\"/hr/employees\" title=\"Employees\" aria-current=\"page\">")
			.contains("Sunita Deshmukh");
	}

	// ------------------------------------------------------------------ profile

	@Test
	void theOverviewShowsPersonalDetailsUnderTheProfileHeader() throws Exception {
		Employee manager = hire("Ravi", "Kale", production, shiroli);
		Employee sunita = complete(hire("Sunita", "Deshmukh", production, shiroli));
		sunita.setReportingManager(manager);
		em.flush();

		String page = page(hr("/hr/employees/" + sunita.getId()), "hr/employees/profile");

		assertThat(page).contains("<title>Sunita Deshmukh | Kosh</title>")
			.contains("<li><a href=\"/hr/employees\">Employees</a></li>")
			.contains("<span class=\"avatar avatar-lg\" aria-hidden=\"true\">SD</span>")
			.contains("<p class=\"profile-role\">T16 Machine Operator, T16 Production</p>")
			.contains("<span class=\"mono profile-code\">" + sunita.getCode() + "</span>")
			.contains("<span class=\"badge\" data-status=\"ACTIVE\">Active</span>")
			.contains("<a href=\"/hr/employees/" + manager.getId() + "\">Ravi Kale</a>")
			.contains("<nav class=\"tabs\" aria-label=\"Employee record\">")
			.containsPattern("<a class=\"tab\" href=\"/hr/employees/" + sunita.getId() + "\"\\s+aria-current=\"page\">Overview</a>")
			.contains("<a class=\"tab\" href=\"/hr/employees/" + sunita.getId() + "/job\">Job</a>")
			.contains("<a class=\"tab\" href=\"/hr/employees/" + sunita.getId() + "/history\">History</a>");
		assertThat(page).containsPattern("<dt>Gender</dt>\\s*<dd>Female</dd>")
			.containsPattern("<dt>Date of birth</dt>\\s*<dd>11 Feb 1993 \\(33 years\\)</dd>")
			.containsPattern("<dt>Personal email</dt>\\s*<dd>sunita.d@example.com</dd>")
			.contains("<span class=\"address-line\">Flat 4, Shree Apartments</span>")
			.contains("<span class=\"address-line\">Kolhapur, Maharashtra 416008</span>")
			.containsPattern("<dt>Permanent address</dt>\\s*<dd>\\s*—\\s*</dd>")
			.contains("<span>Anil Deshmukh</span><span> (Brother)</span>")
			.contains("<span class=\"cell-sub mono\">9890011223</span>")
			.doesNotContain(PAN)
			.doesNotContain(ACCOUNT);
	}

	@Test
	void theJobTabShowsPlacementTermsAccountAndDirectReports() throws Exception {
		Employee manager = hire("Ravi", "Kale", production, shiroli);
		manager.setConfirmationDate(LocalDate.of(2024, 10, 1));
		manager.setGrade(em.find(Grade.class, org.create(OrgKind.GRADES, OrgDetails.of("T16-G3", "T16 Supervisors"))));
		manager.setCostCentre(em.find(CostCentre.class, org.create(OrgKind.COST_CENTRES, OrgDetails.of("T16-CC", "T16 Shiroli plant"))));
		User account = users.save(new User("ravi.kale@t16.kosh.test", "Ravi Kale", List.of(Role.EMPLOYEE)));
		manager.setUser(account);
		Employee report = hire("Sunita", "Deshmukh", production, shiroli);
		report.setReportingManager(manager);
		Employee gone = hire("Meena", "Jadhav", assembly, shiroli);
		gone.setReportingManager(manager);
		gone.setStatus(EmployeeStatus.EXITED);
		em.flush();

		String hr = page(hr("/hr/employees/" + manager.getId() + "/job"), "hr/employees/profile");
		assertThat(hr).contains("aria-current=\"page\">Job</a>")
			.containsPattern("<dt>Grade</dt>\\s*<dd>T16 Supervisors</dd>")
			.containsPattern("<dt>Cost centre</dt>\\s*<dd>T16 Shiroli plant</dd>")
			.containsPattern("<dt>Reports to</dt>\\s*<dd>—</dd>")
			.containsPattern("<dt>Joining date</dt>\\s*<dd>1 Apr 2024</dd>")
			.containsPattern("<dt>Length of service</dt>\\s*<dd>2 years 6 months</dd>")
			.containsPattern("<dt>Confirmation date</dt>\\s*<dd>1 Oct 2024</dd>")
			.doesNotContain("<dt>Contract ends</dt>")
			.contains("<span>ravi.kale@t16.kosh.test</span>")
			.doesNotContain("href=\"/admin/users/")
			.contains("<a class=\"cell-title\" href=\"/hr/employees/" + report.getId() + "\">Sunita Deshmukh</a>")
			.contains("<span class=\"cell-sub\">T16 Machine Operator, T16 Production</span>")
			// People who have left are not on the team any more.
			.doesNotContain("Meena Jadhav");

		// Admins get a link to the sign-in account.
		assertThat(page(get("/hr/employees/" + manager.getId() + "/job").with(user("admin@t16.kosh.test").roles("ADMIN")),
				"hr/employees/profile"))
			.contains("<a href=\"/admin/users/" + account.getId() + "\">ravi.kale@t16.kosh.test</a>");

		String reportPage = page(hr("/hr/employees/" + report.getId() + "/job"), "hr/employees/profile");
		assertThat(reportPage).contains("Nobody reports to Sunita.")
			.containsPattern("<dt>Grade</dt>\\s*<dd>—</dd>")
			.containsPattern("<dt>Confirmation date</dt>\\s*<dd>—</dd>")
			.containsPattern("<dt>Sign-in account</dt>\\s*<dd>No sign-in account</dd>");
	}

	@Test
	void theJobTabShowsTheContractEndOfFixedTermStaff() throws Exception {
		Employee contract = hire("Kiran", "More", production, shiroli);
		contract.setEmploymentType(EmploymentType.FIXED_TERM, LocalDate.of(2027, 3, 31));
		em.flush();

		assertThat(page(hr("/hr/employees/" + contract.getId() + "/job"), "hr/employees/profile"))
			.containsPattern("<dt>Contract ends</dt>\\s*<dd>31 Mar 2027</dd>")
			.containsPattern("<dt>Type</dt>\\s*<dd>Fixed term</dd>");
	}

	/**
	 * The service's own tab rule, for the views that will reuse these tabs (T51 {@code /team}, self-service). The
	 * viewer passed in differs from the HR context set up for {@code @PreAuthorize}, which no request can do today:
	 * {@code /hr/**} admits only HR and ADMIN, both salary roles. The access proof for the HR pages is the URL-level
	 * {@link #hrAndAdminSeeTheSalaryTabOtherRolesAreRefusedTheProfile}.
	 */
	@ParameterizedTest
	@EnumSource(Role.class)
	void theSalaryTabIsShownAndServedOnlyToTheSalaryRoles(Role role) {
		Employee sunita = hire("Sunita", "Deshmukh", production, shiroli);
		em.flush();
		Authentication viewer = new TestingAuthenticationToken("viewer@t16.kosh.test", null, role.authority());
		boolean salaryRole = Set.of(Role.ADMIN, Role.HR, Role.PAYROLL, Role.FINANCE).contains(role);

		assertThat(ProfileTab.SALARY.visibleTo(Set.of(role))).isEqualTo(salaryRole);
		Profile overview = directory.profile(sunita.getId(), ProfileTab.OVERVIEW, viewer);
		if (salaryRole) {
			assertThat(overview.tabs()).containsExactly(ProfileTab.values());
			assertThat(directory.profile(sunita.getId(), ProfileTab.SALARY, viewer).tab()).isEqualTo(ProfileTab.SALARY);
		}
		else {
			assertThat(overview.tabs()).doesNotContain(ProfileTab.SALARY).hasSize(ProfileTab.values().length - 1);
			assertThatThrownBy(() -> directory.profile(sunita.getId(), ProfileTab.SALARY, viewer))
				.isInstanceOf(ForbiddenException.class);
		}
	}

	@Test
	void hrAndAdminSeeTheSalaryTabOtherRolesAreRefusedTheProfile() throws Exception {
		Employee sunita = hire("Sunita", "Deshmukh", production, shiroli);
		em.flush();
		String salary = "/hr/employees/" + sunita.getId() + "/salary";

		for (String[] roles : List.of(new String[] { "HR", "EMPLOYEE" }, new String[] { "ADMIN" })) {
			MockHttpServletRequestBuilder as = get("/hr/employees/" + sunita.getId())
				.with(user("viewer@t16.kosh.test").roles(roles));
			assertThat(page(as, "hr/employees/profile")).as(Arrays.toString(roles))
				.contains("<a class=\"tab\" href=\"" + salary + "\">Salary</a>");
			assertThat(page(get(salary).with(user("viewer@t16.kosh.test").roles(roles)), "hr/employees/profile"))
				.containsPattern("<a class=\"tab\" href=\"" + salary + "\"\\s+aria-current=\"page\">Salary</a>")
				.contains("<h2 class=\"empty-title\">No salary assigned yet</h2>")
				.contains("Once Sunita has a salary structure");
		}
		for (String[] roles : List.of(new String[] { "PAYROLL", "EMPLOYEE" }, new String[] { "FINANCE", "EMPLOYEE" },
				new String[] { "MANAGER", "EMPLOYEE" }, new String[] { "EMPLOYEE" })) {
			for (String path : List.of("/hr/employees", "/hr/employees/" + sunita.getId(), salary)) {
				mvc.perform(get(path).with(user("viewer@t16.kosh.test").roles(roles)))
					.andExpect(status().isForbidden());
			}
		}
	}

	@Test
	void hrSeesThePanInFullAndEveryoneElseSeesItMasked() throws Exception {
		Employee sunita = complete(hire("Sunita", "Deshmukh", production, shiroli));
		em.flush();
		String statutory = "/hr/employees/" + sunita.getId() + "/statutory";

		String hr = page(hr(statutory), "hr/employees/profile");
		assertThat(hr).containsPattern("<dt>PAN</dt>\\s*<dd class=\"mono\">" + PAN + "</dd>")
			.containsPattern("<dt>UAN</dt>\\s*<dd class=\"mono\">100987654321</dd>")
			.containsPattern("<dt>ESI IP number</dt>\\s*<dd class=\"mono\">3100456789</dd>")
			.contains("<span>Restrict to the wage ceiling</span>")
			.contains("Exempt<span class=\"cell-sub\">Serving member of the armed forces</span>")
			.containsPattern("<dt>Person with disability</dt>\\s*<dd>Yes</dd>")
			.doesNotContain("Only HR sees it in full");

		String admin = page(get(statutory).with(user("admin@t16.kosh.test").roles("ADMIN")), "hr/employees/profile");
		assertThat(admin).containsPattern("<dt>PAN</dt>\\s*<dd class=\"mono\">ABXXXXX4F</dd>")
			.contains("The PAN is masked. Only HR sees it in full.")
			.doesNotContain(PAN);

		Employee noNumbers = hire("Ravi", "Kale", production, shiroli);
		em.flush();
		assertThat(page(hr("/hr/employees/" + noNumbers.getId() + "/statutory"), "hr/employees/profile"))
			.containsPattern("<dt>PAN</dt>\\s*<dd class=\"mono\">—</dd>")
			.containsPattern("<dt>UAN</dt>\\s*<dd class=\"mono\">—</dd>")
			.contains("Deducted where the work location charges it");
	}

	@Test
	void theBankTabMasksTheAccountNumberForEveryViewer() throws Exception {
		Employee sunita = complete(hire("Sunita", "Deshmukh", production, shiroli));
		Employee ravi = hire("Ravi", "Kale", production, shiroli);
		em.flush();
		String bank = "/hr/employees/" + sunita.getId() + "/bank";

		for (MockHttpServletRequestBuilder request : List.of(hr(bank),
				get(bank).with(user("admin@t16.kosh.test").roles("ADMIN")))) {
			assertThat(page(request, "hr/employees/profile"))
				.containsPattern("<dt>Account number</dt>\\s*<dd class=\"mono\">XXXXXX4321</dd>")
				.containsPattern("<dt>IFSC</dt>\\s*<dd class=\"mono\">MAHB0000123</dd>")
				.containsPattern("<dt>Bank</dt>\\s*<dd>Bank of Maharashtra</dd>")
				.containsPattern("<dt>Account holder</dt>\\s*<dd>Sunita R Deshmukh</dd>")
				.doesNotContain(ACCOUNT);
		}
		assertThat(page(hr("/hr/employees/" + ravi.getId() + "/bank"), "hr/employees/profile"))
			.contains("<h2 class=\"empty-title\">No bank details yet</h2>")
			.contains("Ravi can’t be paid by bank transfer until a salary account is recorded.")
			.contains("href=\"/hr/employees/" + ravi.getId() + "/edit?step=bank\"", "Add bank details");
	}

	@Test
	void documentsHasAnEmptyStateAndHistoryListsTheDatedEventsOldestFirst() throws Exception {
		Employee kiran = hire("Kiran", "More", production, shiroli);
		kiran.setEmploymentType(EmploymentType.FIXED_TERM, LocalDate.of(2027, 3, 31));
		kiran.setConfirmationDate(LocalDate.of(2024, 10, 1));
		em.flush();

		assertThat(page(hr("/hr/employees/" + kiran.getId() + "/documents"), "hr/employees/profile"))
			.contains("aria-current=\"page\">Documents</a>")
			.contains("<h2 class=\"empty-title\">No documents yet</h2>");

		String history = page(hr("/hr/employees/" + kiran.getId() + "/history"), "hr/employees/profile");
		assertThat(history).contains("<li class=\"timeline-item is-success\">")
			.contains("<p class=\"timeline-title\">Joined</p>")
			// The record holds only today's type and location, so the history claims neither for the join.
			.doesNotContain("timeline-body")
			.contains("<p class=\"timeline-title\">Confirmed</p>")
			.contains("<li class=\"timeline-item is-warning\">")
			.contains("<p class=\"timeline-title\">Contract ends</p>")
			.contains("<p class=\"timeline-title\">Record added to Kosh</p>");
		assertThat(order(history, ">Joined</p>", ">Confirmed</p>", ">Record added to Kosh</p>", ">Contract ends</p>")).isTrue();
	}

	@Test
	void historySaysWhatIsStillAhead() {
		Employee joiner = hire("Aarti", "Pawar", production, shiroli);
		joiner.setJoiningDate(LocalDate.of(2026, 11, 2));
		joiner.setEmploymentType(EmploymentType.PROBATION, null);
		joiner.setConfirmationDate(LocalDate.of(2027, 5, 2));
		em.flush();

		Profile profile = directory.profile(joiner.getId(), ProfileTab.HISTORY, hrViewer());
		assertThat(profile.history()).extracting(Event::title)
			.containsExactly("Record added to Kosh", "Joins", "Confirmation due");
		assertThat(profile.service()).isEqualTo("Joins on 2 Nov 2026");
		assertThat(profile.history().get(2).tone()).isEqualTo("info");
	}

	@Test
	void lengthOfServiceCountsYearsAndMonths() {
		Employee recent = hire("Aarti", "Pawar", production, shiroli);
		recent.setJoiningDate(LocalDate.of(2026, 9, 20));
		Employee year = hire("Mohan", "Gaikwad", production, shiroli);
		year.setJoiningDate(LocalDate.of(2025, 10, 5));
		Employee months = hire("Zubin", "Shaikh", production, shiroli);
		months.setJoiningDate(LocalDate.of(2026, 7, 4));
		em.flush();

		assertThat(directory.profile(recent.getId(), ProfileTab.JOB, hrViewer()).service()).isEqualTo("Less than a month");
		assertThat(directory.profile(year.getId(), ProfileTab.JOB, hrViewer()).service()).isEqualTo("1 year");
		assertThat(directory.profile(months.getId(), ProfileTab.JOB, hrViewer()).service()).isEqualTo("3 months");
	}

	@Test
	void unknownEmployeesAndTabsAreNotFound() throws Exception {
		Employee sunita = hire("Sunita", "Deshmukh", production, shiroli);
		em.flush();

		for (String path : List.of("/hr/employees/999999", "/hr/employees/999999/bank",
				"/hr/employees/" + sunita.getId() + "/payslips", "/hr/employees/" + sunita.getId() + "/overview")) {
			mvc.perform(get(path).with(user(HR).roles("HR", "EMPLOYEE")))
				.andExpect(status().isNotFound())
				.andExpect(view().name("error/404"));
		}
	}

	@Test
	void anonymousVisitorsAreSentToSignIn() throws Exception {
		Employee sunita = hire("Sunita", "Deshmukh", production, shiroli);
		em.flush();
		// The HR user set up for the organisation services would otherwise sign these requests in.
		SecurityContextHolder.clearContext();

		for (String path : List.of("/hr/employees", "/hr/employees/" + sunita.getId(),
				"/hr/employees/" + sunita.getId() + "/salary")) {
			mvc.perform(get(path)).andExpect(status().isFound()).andExpect(redirectedUrl("/login"));
		}
	}

	@Test
	void theServiceItselfIsForHrAndAdminOnly() {
		Employee sunita = hire("Sunita", "Deshmukh", production, shiroli);
		em.flush();
		Authentication payroll = new TestingAuthenticationToken("payroll@t16.kosh.test", null, "ROLE_PAYROLL");
		SecurityContextHolder.getContext().setAuthentication(payroll);

		EmployeeFilter everyone = new EmployeeFilter(null, null, null, null, null);
		assertThatThrownBy(() -> directory.list(everyone, PageRequest.of(0, 25))).isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> directory.statusCounts(everyone)).isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> directory.profile(sunita.getId(), ProfileTab.OVERVIEW, payroll))
			.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	void listAndProfileLoadWhatThePagesShowInTheirOwnQueries() {
		Employee manager = hire("Ravi", "Kale", production, shiroli);
		Employee sunita = hire("Sunita", "Deshmukh", production, shiroli);
		sunita.setReportingManager(manager);
		em.flush();
		em.clear();

		Page<Employee> page = directory.list(new EmployeeFilter("", null, null, null, null),
				PageRequest.of(0, 25, Sort.by(Sort.Order.asc("department.name").ignoreCase())));
		assertThat(page.getContent()).hasSize(2).allSatisfy(row -> {
			assertThat(Hibernate.isInitialized(row.getDepartment())).isTrue();
			assertThat(Hibernate.isInitialized(row.getDesignation())).isTrue();
			assertThat(Hibernate.isInitialized(row.getLocation())).isTrue();
		});
		em.clear();
		Employee loaded = directory.profile(sunita.getId(), ProfileTab.JOB, hrViewer()).employee();
		assertThat(Hibernate.isInitialized(loaded.getReportingManager())).isTrue();
		assertThat(Hibernate.isInitialized(loaded.getDepartment())).isTrue();
		em.clear();
		Employee reportOf = directory.profile(manager.getId(), ProfileTab.JOB, hrViewer()).reports().getFirst();
		assertThat(Hibernate.isInitialized(reportOf.getDesignation())).isTrue();
		assertThat(Hibernate.isInitialized(reportOf.getDepartment())).isTrue();
	}

	// ------------------------------------------------------------------ helpers

	private String page(MockHttpServletRequestBuilder request) throws Exception {
		return page(request, "hr/employees/list");
	}

	private String page(MockHttpServletRequestBuilder request, String view) throws Exception {
		return mvc.perform(request).andExpect(status().isOk()).andExpect(view().name(view)).andReturn().getResponse()
			.getContentAsString();
	}

	/** A GET as an HR user. */
	private static MockHttpServletRequestBuilder hr(String path) {
		return get(path).with(user(HR).roles("HR", "EMPLOYEE"));
	}

	/** Whether the texts appear in this order. */
	private static boolean order(String page, String... texts) {
		int at = -1;
		for (String text : texts) {
			int next = page.indexOf(text, at + 1);
			if (next < 0) {
				return false;
			}
			at = next;
		}
		return true;
	}

	private Employee hire(String firstName, String lastName, Department department, WorkLocation location) {
		Employee employee = new Employee(employees.nextCode());
		employee.setFirstName(firstName);
		employee.setLastName(lastName);
		employee.setGender(Gender.MALE);
		employee.setDateOfBirth(LocalDate.of(1990, 6, 15));
		employee.setMobile("9822012345");
		employee.setJoiningDate(LocalDate.of(2024, 4, 1));
		employee.setEmploymentType(EmploymentType.PERMANENT, null);
		employee.setDepartment(department);
		employee.setDesignation(operator);
		employee.setLocation(location);
		employee.setPfPolicy(PfPolicy.RESTRICT);
		return employees.save(employee);
	}

	/** Personal, statutory and bank details filled in. */
	private Employee complete(Employee employee) {
		employee.setGender(Gender.FEMALE);
		employee.setDateOfBirth(LocalDate.of(1993, 2, 11));
		employee.setPersonalEmail("sunita.d@example.com");
		employee.setCurrentAddress(new Address("Flat 4, Shree Apartments", "Rajarampuri 5th Lane", "Kolhapur",
				IndianState.MH, "416008"));
		employee.setEmergencyContact(new EmergencyContact("Anil Deshmukh", "Brother", "9890011223"));
		employee.setPan(PAN, hasher);
		employee.setUan("100987654321");
		employee.setEsiIpNumber("3100456789");
		employee.setPtExemption(true, "Serving member of the armed forces");
		employee.setPersonWithDisability(true);
		employee.setBankAccount(new BankAccount("Bank of Maharashtra", "MAHB0000123", ACCOUNT, "Sunita R Deshmukh"));
		return employee;
	}

	private WorkLocation location(String code, String name) {
		return em.find(WorkLocation.class, org.create(OrgKind.LOCATIONS,
				new OrgDetails(code, name, null, "Plot B-12, MIDC Shiroli", null, "Kolhapur", IndianState.MH, "416122", true)));
	}

	private static Authentication hrViewer() {
		return new TestingAuthenticationToken(HR, null, "ROLE_HR");
	}

	private static void asHr() {
		SecurityContextHolder.getContext().setAuthentication(hrViewer());
	}

}
