package com.kosh.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;

import com.kosh.audit.AuditAction;
import com.kosh.audit.AuditEvent;
import com.kosh.audit.AuditEventRepository;
import com.kosh.common.BusinessRuleException;
import com.kosh.common.IndianState;
import com.kosh.user.Role;
import com.kosh.user.User;
import com.kosh.user.UserRepository;
import com.kosh.web.Flash;

/**
 * The organisation structure pages (PLAN T12): lists, adding and editing with inline errors, unique codes and names,
 * department heads, work locations, deactivating, deleting an unused item, the audit trail, and that only HR and ADMIN
 * get in. Each test runs in a rolled-back transaction; {@link OrgInUseTest} covers the refused delete of an item in
 * use, which must commit to be real.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class OrgStructureTest {

	/** The table behind each kind (migration V5). */
	static final Map<OrgKind, String> TABLES = Map.of(OrgKind.DEPARTMENTS, "departments", OrgKind.DESIGNATIONS,
			"designations", OrgKind.GRADES, "grades", OrgKind.COST_CENTRES, "cost_centres", OrgKind.LOCATIONS,
			"work_locations");

	private final MockMvc mvc;

	private final OrgService service;

	private final UserRepository users;

	private final AuditEventRepository events;

	private final JdbcTemplate jdbc;

	private final EntityManager entityManager;

	@Autowired
	OrgStructureTest(MockMvc mvc, OrgService service, UserRepository users, AuditEventRepository events,
			JdbcTemplate jdbc, EntityManager entityManager) {
		this.mvc = mvc;
		this.service = service;
		this.users = users;
		this.events = events;
		this.jdbc = jdbc;
		this.entityManager = entityManager;
	}

	// ------------------------------------------------------------------ lists

	@Test
	void organisationOpensOnTheDepartments() throws Exception {
		mvc.perform(get("/hr/org").with(hr())).andExpect(redirectedUrl("/hr/org/departments"));
	}

	@Test
	void anEmptyListShowsTheTabsAndHowToAddTheFirstItem() throws Exception {
		String page = page(get("/hr/org/departments").with(hr()), "hr/org/list");
		assertThat(page).contains("<title>Departments | Kosh</title>")
			.contains("<a class=\"nav-link\" href=\"/hr/org\" title=\"Organisation\" aria-current=\"page\">")
			.contains("<a href=\"/hr/org\">Organisation</a>")
			.contains("href=\"/hr/org/departments\" aria-current=\"page\"")
			.contains("href=\"/hr/org/designations\"", "href=\"/hr/org/grades\"", "href=\"/hr/org/cost-centres\"",
					"href=\"/hr/org/locations\"")
			.contains("<span class=\"tab-count\">0</span>")
			.contains("No departments yet")
			.contains("Add the departments people work in, such as Production or Accounts.")
			.contains("href=\"/hr/org/departments/new\"")
			.doesNotContain("<table");
	}

	@Test
	void listsSortByNameOrCodeAndIgnoreOtherSortKeys() throws Exception {
		create(OrgKind.GRADES, "G2", "Operators");
		create(OrgKind.GRADES, "G1", "Trainees");
		create(OrgKind.GRADES, "G3", "Managers");
		create(OrgKind.GRADES, "G4", "apprentices");

		// Case-insensitive on every database: H2 alone would put "apprentices" last.
		String byName = page(get("/hr/org/grades").with(hr()), "hr/org/list");
		assertThat(byName).containsSubsequence("apprentices", "Managers", "Operators", "Trainees")
			.contains("<span class=\"tab-count\">4</span>");
		String byCode = page(get("/hr/org/grades").queryParam("sort", "code,asc").with(hr()), "hr/org/list");
		assertThat(byCode).containsSubsequence(">G1<", ">G2<", ">G3<").contains("aria-sort=\"ascending\"");
		String byVersion = page(get("/hr/org/grades").queryParam("sort", "version,desc").with(hr()), "hr/org/list");
		assertThat(byVersion).containsSubsequence("Managers", "Operators", "Trainees");
	}

	@Test
	void unknownListsAndItemsAreNotFound() throws Exception {
		mvc.perform(get("/hr/org/teams").with(hr())).andExpect(status().isNotFound());
		mvc.perform(get("/hr/org/departments/999999").with(hr())).andExpect(status().isNotFound());
		mvc.perform(post("/hr/org/grades/999999/delete").with(hr()).with(csrf())).andExpect(status().isNotFound());
		mvc.perform(post("/hr/org/grades/999999/deactivate").with(hr()).with(csrf()))
			.andExpect(status().isNotFound());
	}

	// ------------------------------------------------------------------ departments and heads

	@Test
	void addingADepartmentWithAHeadTidiesTheInputListsItAndAuditsIt() throws Exception {
		User meera = person("meera@org.kosh.test", "Meera Deshpande", Role.EMPLOYEE, Role.MANAGER);
		String form = page(get("/hr/org/departments/new").with(hr()), "hr/org/edit");
		assertThat(form).contains("<title>Add department | Kosh</title>")
			.contains("<option value=\"\">No head</option>")
			.contains("<option value=\"" + meera.getId() + "\">Meera Deshpande (meera@org.kosh.test)</option>")
			.contains("like PROD. Used in import files and reports.")
			.contains("About departments")
			.doesNotContain("Delete department")
			.doesNotContain("PIN code");

		mvc.perform(post("/hr/org/departments").with(hr())
			.with(csrf())
			.param("code", " prod ")
			.param("name", "  Production   shop ")
			.param("headUserId", meera.getId().toString()))
			.andExpect(redirectedUrl("/hr/org/departments"))
			.andExpect(flash().attribute(Flash.ATTRIBUTE, Flash.success("Production shop added")));

		long id = id(OrgKind.DEPARTMENTS, "PROD");
		assertThat(jdbc.queryForObject("select name from departments where id = ?", String.class, id))
			.isEqualTo("Production shop");
		assertThat(jdbc.queryForObject("select head_user_id from departments where id = ?", Long.class, id))
			.isEqualTo(meera.getId());
		String list = page(get("/hr/org/departments").with(hr()), "hr/org/list");
		assertThat(list).contains(">PROD<", ">Production shop</a>", ">Meera Deshpande<", ">Active<")
			.contains("<span class=\"tab-count\">1</span>");

		AuditEvent event = only(AuditAction.ORG_ITEM_ADDED, "Department", id);
		assertThat(event.getSummary()).isEqualTo("Added department Production shop (PROD)");
		assertThat(event.getActorEmail()).isEqualTo("hr@org.kosh.test");
		assertThat(event.getAfterJson()).isEqualTo(
				"{\"Code\":\"PROD\",\"Name\":\"Production shop\",\"Head\":\"Meera Deshpande (meera@org.kosh.test)\"}");
	}

	@Test
	void badInputShowsEveryErrorInlineKeepsTheInputAndSavesNothing() throws Exception {
		String page = page(post("/hr/org/departments").with(hr())
			.with(csrf())
			.param("code", "P")
			.param("name", " ")
			.param("headUserId", "nobody"), "hr/org/edit");
		assertThat(page).contains("Enter a name.")
			.contains("Use 2 to 10 letters, digits or hyphens, starting with a letter or digit.")
			.contains("Choose a value from the list.")
			.contains("value=\"P\"")
			.contains("aria-invalid=\"true\"");
		assertThat(count(OrgKind.DEPARTMENTS)).isZero();
		assertThat(events.findAll()).noneMatch(event -> event.getAction() == AuditAction.ORG_ITEM_ADDED);
	}

	@Test
	void codesAndNamesAreUniqueWithinAKindInAnyCase() throws Exception {
		create(OrgKind.DEPARTMENTS, "PROD", "Production");
		String page = page(post("/hr/org/departments").with(hr())
			.with(csrf())
			.param("code", "prod")
			.param("name", "PRODUCTION"), "hr/org/edit");
		assertThat(page).contains("Another department already uses this code.")
			.contains("Another department already has this name.");
		assertThat(count(OrgKind.DEPARTMENTS)).isEqualTo(1);

		// Another kind may use the same code and name.
		create(OrgKind.COST_CENTRES, "PROD", "Production");
	}

	@Test
	void aDuplicateOfAnInactiveItemSaysToActivateIt() throws Exception {
		long id = create(OrgKind.DESIGNATIONS, "OPR", "Machine Operator");
		mvc.perform(post("/hr/org/designations/{id}/deactivate", id).with(hr()).with(csrf()));
		String page = page(post("/hr/org/designations").with(hr())
			.with(csrf())
			.param("code", "OPR2")
			.param("name", "machine operator"), "hr/org/edit");
		assertThat(page)
			.contains("An inactive designation, Machine Operator, already has this name. Activate it instead.");
	}

	@Test
	void theHeadPickerOffersEnabledEmployeesOnly() throws Exception {
		User asha = person("asha@org.kosh.test", "Asha Patil", Role.EMPLOYEE);
		User ravi = person("ravi@org.kosh.test", "Ravi Jadhav", Role.EMPLOYEE);
		ravi.setEnabled(false);
		User root = person("root@org.kosh.test", "Root Admin", Role.ADMIN);

		String form = page(get("/hr/org/departments/new").with(hr()), "hr/org/edit");
		assertThat(form).contains("Asha Patil (asha@org.kosh.test)")
			.doesNotContain("Ravi Jadhav")
			.doesNotContain("Root Admin");

		assertThat(page(post("/hr/org/departments").with(hr())
			.with(csrf())
			.param("code", "QA")
			.param("name", "Quality")
			.param("headUserId", ravi.getId().toString()), "hr/org/edit"))
			.contains(OrgService.HEAD_NOT_ELIGIBLE);
		assertThat(page(post("/hr/org/departments").with(hr())
			.with(csrf())
			.param("code", "QA")
			.param("name", "Quality")
			.param("headUserId", root.getId().toString()), "hr/org/edit"))
			.contains(OrgService.HEAD_NOT_ELIGIBLE);
		assertThat(page(post("/hr/org/departments").with(hr())
			.with(csrf())
			.param("code", "QA")
			.param("name", "Quality")
			.param("headUserId", "999999"), "hr/org/edit"))
			.contains(OrgService.HEAD_NOT_FOUND);
		assertThat(count(OrgKind.DEPARTMENTS)).isZero();

		mvc.perform(post("/hr/org/departments").with(hr())
			.with(csrf())
			.param("code", "QA")
			.param("name", "Quality")
			.param("headUserId", asha.getId().toString())).andExpect(redirectedUrl("/hr/org/departments"));
	}

	@Test
	void aHeadWhoseAccountWasDisabledStaysUntilSomeoneElseIsChosen() throws Exception {
		User asha = person("asha@org.kosh.test", "Asha Patil", Role.EMPLOYEE);
		long id = create(OrgKind.DEPARTMENTS, "QA", "Quality", "headUserId", asha.getId().toString());
		asha.setEnabled(false);

		String page = page(get("/hr/org/departments/{id}", id).with(hr()), "hr/org/edit");
		assertThat(page).contains("<option value=\"" + asha.getId() + "\" selected=\"selected\">Asha Patil (asha@org.kosh.test)</option>");
		mvc.perform(post("/hr/org/departments/{id}", id).with(hr())
			.with(csrf())
			.param("code", "QA")
			.param("name", "Quality Assurance")
			.param("headUserId", asha.getId().toString()))
			.andExpect(redirectedUrl("/hr/org/departments"))
			.andExpect(flash().attribute(Flash.ATTRIBUTE,
					Flash.success("Saved Quality Assurance").withText("Changed: Name.")));
	}

	@Test
	void editingShowsWhatChangedAndAuditsBeforeAndAfter() throws Exception {
		User meera = person("meera@org.kosh.test", "Meera Deshpande", Role.EMPLOYEE);
		long id = create(OrgKind.DEPARTMENTS, "PROD", "Production");

		String page = page(get("/hr/org/departments/{id}", id).with(hr()), "hr/org/edit");
		assertThat(page).contains("<title>Production | Kosh</title>")
			.contains("<a href=\"/hr/org/departments\">Departments</a>")
			.contains("value=\"Production\"", "value=\"PROD\"")
			.contains("action=\"/hr/org/departments/" + id + "\"")
			.contains("Deactivate", "Delete department", "Every change is recorded in the audit log")
			.doesNotContain("View in audit log");
		// Delete asks first: the button opens a dialog, and only the dialog's form posts.
		assertThat(page).contains(
				"<button class=\"btn btn-danger btn-sm\" type=\"button\" commandfor=\"delete-dialog\" command=\"show-modal\">")
			.containsSubsequence("<dialog class=\"modal\" id=\"delete-dialog\"", "Delete Production?",
					"It is removed from the departments list. This can’t be undone.", "<form method=\"dialog\">",
					"action=\"/hr/org/departments/" + id + "/delete\"", "</dialog>");
		assertThat(page(get("/hr/org/departments/{id}", id).with(admin()), "hr/org/edit"))
			.contains("href=\"/admin/audit?entity=Department&amp;entityId=" + id + "\"");

		mvc.perform(post("/hr/org/departments/{id}", id).with(hr())
			.with(csrf())
			.param("code", "PROD")
			.param("name", "Production shop")
			.param("headUserId", meera.getId().toString()))
			.andExpect(redirectedUrl("/hr/org/departments"))
			.andExpect(flash().attribute(Flash.ATTRIBUTE,
					Flash.success("Saved Production shop").withText("Changed: Name, Head.")));
		AuditEvent changed = only(AuditAction.ORG_ITEM_CHANGED, "Department", id);
		assertThat(changed.getSummary()).isEqualTo("Changed department Production shop: Name, Head");
		assertThat(changed.getBeforeJson()).isEqualTo("{\"Name\":\"Production\",\"Head\":null}");
		assertThat(changed.getAfterJson())
			.isEqualTo("{\"Name\":\"Production shop\",\"Head\":\"Meera Deshpande (meera@org.kosh.test)\"}");

		mvc.perform(post("/hr/org/departments/{id}", id).with(hr())
			.with(csrf())
			.param("code", "prod")
			.param("name", "Production shop")
			.param("headUserId", meera.getId().toString()))
			.andExpect(flash().attribute(Flash.ATTRIBUTE,
					Flash.info("Nothing to save").withText("Production shop is as it was.")));
		assertThat(events(AuditAction.ORG_ITEM_CHANGED, "Department", id)).hasSize(1);
	}

	@Test
	void anItemKeepsItsOwnCodeAndNameWhenSaved() throws Exception {
		long id = create(OrgKind.GRADES, "G1", "Trainees");
		mvc.perform(post("/hr/org/grades/{id}", id).with(hr()).with(csrf()).param("code", "G1").param("name", "trainees"))
			.andExpect(redirectedUrl("/hr/org/grades"))
			.andExpect(flash().attribute(Flash.ATTRIBUTE, Flash.success("Saved trainees").withText("Changed: Name.")));
	}

	// ------------------------------------------------------------------ work locations

	@Test
	void aWorkLocationNeedsAnAddressStateAndPinCode() throws Exception {
		String form = page(get("/hr/org/locations/new").with(hr()), "hr/org/edit");
		assertThat(form).contains("<title>Add work location | Kosh</title>")
			.contains("<option value=\"MH\">Maharashtra</option>")
			.contains("Deduct professional tax")
			.containsPattern("<input class=\"switch\" type=\"checkbox\" role=\"switch\"[^>]*checked=\"checked\"")
			.doesNotContain("No head");

		String page = page(post("/hr/org/locations").with(hr())
			.with(csrf())
			.param("code", "SHR")
			.param("name", "MIDC Shiroli factory")
			.param("state", "XX")
			.param("pincode", "016122"), "hr/org/edit");
		assertThat(page).contains("Enter the address.", "Enter the city.", "Choose a value from the list.",
				"Enter a 6-digit PIN code.");
		assertThat(page(post("/hr/org/locations").with(hr()).with(csrf()).param("code", "SHR").param("name", "Shiroli"),
				"hr/org/edit"))
			.contains("Choose the state.", "Enter the PIN code.");
		assertThat(count(OrgKind.LOCATIONS)).isZero();
	}

	@Test
	void workLocationsListTheirPlaceAndWhetherPtIsDeducted() throws Exception {
		long shiroli = create(OrgKind.LOCATIONS, "SHR", "MIDC Shiroli factory");
		mvc.perform(post("/hr/org/locations").with(hr())
			.with(csrf())
			.param("code", "DEL")
			.param("name", "Delhi liaison office")
			.param("addressLine1", "14 Barakhamba Road")
			.param("city", "New Delhi")
			.param("state", "DL")
			.param("pincode", "110001")
			.param("_ptApplicable", "on")).andExpect(redirectedUrl("/hr/org/locations"));

		assertThat(jdbc.queryForMap("select state_code, pt_applicable, pincode from work_locations where id = ?",
				shiroli))
			.containsEntry("state_code", "MH")
			.containsEntry("pt_applicable", true)
			.containsEntry("pincode", "416122");
		String list = page(get("/hr/org/locations").with(hr()), "hr/org/list");
		assertThat(list).contains("<title>Work locations | Kosh</title>")
			.contains(">Kolhapur, Maharashtra<", ">New Delhi, Delhi<")
			.containsSubsequence("Delhi liaison office", "Not deducted", "MIDC Shiroli factory", "Deducted");

		// A switch turned off posts only Spring's "_ptApplicable" marker.
		LinkedMultiValueMap<String, String> moved = params(OrgKind.LOCATIONS, "SHR", "MIDC Shiroli factory");
		moved.set("state", "KA");
		moved.remove("ptApplicable");
		mvc.perform(post("/hr/org/locations/{id}", shiroli).with(hr()).with(csrf()).params(moved))
			.andExpect(flash().attribute(Flash.ATTRIBUTE,
					Flash.success("Saved MIDC Shiroli factory").withText("Changed: State, Professional tax.")));
		AuditEvent changed = only(AuditAction.ORG_ITEM_CHANGED, "Work location", shiroli);
		assertThat(changed.getBeforeJson()).isEqualTo("{\"State\":\"Maharashtra\",\"Professional tax\":\"Deducted\"}");
		assertThat(changed.getAfterJson()).isEqualTo("{\"State\":\"Karnataka\",\"Professional tax\":\"Not deducted\"}");
	}

	// ------------------------------------------------------------------ status and delete

	@ParameterizedTest
	@EnumSource(OrgKind.class)
	void everyKindCanBeAddedDeactivatedActivatedAndDeletedWhileUnused(OrgKind kind) throws Exception {
		String base = "/hr/org/" + kind.slug();
		long id = create(kind, "ZX-1", "Probe " + kind.noun());
		String name = "Probe " + kind.noun();

		mvc.perform(post(base + "/{id}/deactivate", id).with(hr()).with(csrf()))
			.andExpect(redirectedUrl(base + "/" + id))
			.andExpect(flash().attribute(Flash.ATTRIBUTE, Flash.success(name + " deactivated")
				.withText("It stays on the records that have it but can’t be chosen for new ones.")));
		assertThat(page(get(base + "/{id}", id).with(hr()), "hr/org/edit")).contains(">Inactive<")
			.contains("action=\"" + base + "/" + id + "/activate\"")
			.doesNotContain("/deactivate\"");
		assertThat(page(get(base).with(hr()), "hr/org/list")).contains(">Inactive<");
		// Deactivating again changes nothing and records nothing.
		mvc.perform(post(base + "/{id}/deactivate", id).with(hr()).with(csrf()));

		mvc.perform(post(base + "/{id}/activate", id).with(hr()).with(csrf()))
			.andExpect(redirectedUrl(base + "/" + id))
			.andExpect(flash().attribute(Flash.ATTRIBUTE,
					Flash.success(name + " is active again").withText("It can be chosen for new records.")));

		mvc.perform(post(base + "/{id}/delete", id).with(hr()).with(csrf()))
			.andExpect(redirectedUrl(base))
			.andExpect(flash().attribute(Flash.ATTRIBUTE, Flash.success(name + " deleted")));
		assertThat(count(kind)).isZero();
		mvc.perform(get(base + "/{id}", id).with(hr())).andExpect(status().isNotFound());

		List<AuditEvent> trail = events.findAll()
			.stream()
			.filter(event -> kind.label().equals(event.getEntityType()) && Long.valueOf(id).equals(event.getEntityId()))
			.toList();
		assertThat(trail).extracting(AuditEvent::getAction)
			.containsExactly(AuditAction.ORG_ITEM_ADDED, AuditAction.ORG_ITEM_DEACTIVATED,
					AuditAction.ORG_ITEM_ACTIVATED, AuditAction.ORG_ITEM_DELETED);
		assertThat(trail.getLast().getSummary()).isEqualTo("Deleted %s %s (ZX-1)".formatted(kind.noun(), name));
		assertThat(trail.getLast().getBeforeJson()).startsWith("{\"Code\":\"ZX-1\",\"Name\":\"%s\"".formatted(name));
		assertThat(trail.getLast().getAfterJson()).startsWith("{\"Code\":null,\"Name\":null").doesNotContain("ZX-1");
	}

	// ------------------------------------------------------------------ who may do what

	@ParameterizedTest
	@ValueSource(strings = { "PAYROLL", "FINANCE", "MANAGER", "EMPLOYEE" })
	void onlyHrAndAdminsGetIn(String role) throws Exception {
		long id = create(OrgKind.DEPARTMENTS, "PROD", "Production");
		RequestPostProcessor other = user("other@org.kosh.test").roles(role, "EMPLOYEE");
		for (String path : List.of("/hr/org", "/hr/org/departments", "/hr/org/locations/new", "/hr/org/departments/" + id)) {
			mvc.perform(get(path).with(other)).andExpect(status().isForbidden());
		}
		for (String path : List.of("/hr/org/departments", "/hr/org/departments/" + id,
				"/hr/org/departments/" + id + "/deactivate", "/hr/org/departments/" + id + "/activate",
				"/hr/org/departments/" + id + "/delete")) {
			mvc.perform(post(path).with(other).with(csrf()).param("code", "HACK").param("name", "Hacked"))
				.andExpect(status().isForbidden());
		}
		entityManager.flush();
		assertThat(jdbc.queryForMap("select code, name, active from departments where id = ?", id))
			.containsEntry("code", "PROD")
			.containsEntry("name", "Production")
			.containsEntry("active", true);
	}

	@Test
	void anonymousVisitorsAreSentToSignIn() throws Exception {
		mvc.perform(get("/hr/org/departments")).andExpect(redirectedUrl("/login"));
		mvc.perform(post("/hr/org/departments").with(csrf()).param("code", "X1").param("name", "X"))
			.andExpect(redirectedUrl("/login"));
		assertThat(count(OrgKind.DEPARTMENTS)).isZero();
	}

	@Test
	void anAdminCanManageTheStructureToo() throws Exception {
		page(get("/hr/org/cost-centres").with(admin()), "hr/org/list");
		mvc.perform(post("/hr/org/cost-centres").with(admin()).with(csrf()).param("code", "CC-110").param("name", "Shiroli plant"))
			.andExpect(redirectedUrl("/hr/org/cost-centres"));
		assertThat(only(AuditAction.ORG_ITEM_ADDED, "Cost centre", id(OrgKind.COST_CENTRES, "CC-110")).getActorEmail())
			.isEqualTo("admin@org.kosh.test");
	}

	@Test
	@WithMockUser(username = "payroll@org.kosh.test", roles = { "PAYROLL", "FINANCE", "MANAGER", "EMPLOYEE" })
	void theServiceRefusesEveryoneButHrAndAdmins() {
		assertThatThrownBy(() -> service.list(OrgKind.DEPARTMENTS, Sort.by("name")))
			.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> service.create(OrgKind.GRADES, OrgDetails.of("G1", "Trainees")))
			.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> service.setActive(OrgKind.GRADES, 1L, false)).isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> service.delete(OrgKind.GRADES, 1L)).isInstanceOf(AccessDeniedException.class);
	}

	@Test
	@WithMockUser(username = "hr@org.kosh.test", roles = { "HR", "EMPLOYEE" })
	void theServiceChecksTheRulesWhateverTheCaller() {
		assertThat(OrgDetails.of(" cc 110 ", "  Shiroli \t plant ")).isEqualTo(OrgDetails.of("CC110", "Shiroli plant"));
		assertRefused(() -> service.create(OrgKind.GRADES, OrgDetails.of(null, "Trainees")), "code");
		assertRefused(() -> service.create(OrgKind.GRADES, OrgDetails.of("-G1", "Trainees")), "code");
		assertRefused(() -> service.create(OrgKind.GRADES, OrgDetails.of("G1234567890", "Trainees")), "code");
		assertRefused(() -> service.create(OrgKind.GRADES, OrgDetails.of("G1", "x".repeat(101))), "name");
		assertRefused(() -> service.create(OrgKind.LOCATIONS, OrgDetails.of("SHR", "Shiroli")), "addressLine1");
		assertRefused(() -> service.create(OrgKind.LOCATIONS, new OrgDetails("SHR", "Shiroli", null, "Plot C-14",
				"y".repeat(151), "Kolhapur", IndianState.MH, "416122", true)), "addressLine2");
		assertRefused(() -> service.create(OrgKind.DEPARTMENTS,
				new OrgDetails("QA", "Quality", 999_999L, null, null, null, null, null, false)), "headUserId");

		long id = service.create(OrgKind.GRADES, OrgDetails.of("G1", "x".repeat(100)));
		assertRefused(() -> service.update(OrgKind.GRADES, id, OrgDetails.of("G1", " ")), "name");
		assertThat(service.problems(OrgKind.GRADES, null, OrgDetails.of("g1", "X".repeat(100))))
			.extracting(OrgService.Problem::field)
			.containsExactly("name", "code");
	}

	// ------------------------------------------------------------------ helpers

	static RequestPostProcessor hr() {
		return user("hr@org.kosh.test").roles("HR", "EMPLOYEE");
	}

	static RequestPostProcessor admin() {
		return user("admin@org.kosh.test").roles("ADMIN");
	}

	/** What the add form posts for a kind: a code and a name, plus a full address in Maharashtra for a location. */
	static LinkedMultiValueMap<String, String> params(OrgKind kind, String code, String name) {
		LinkedMultiValueMap<String, String> params = new LinkedMultiValueMap<>();
		params.add("code", code);
		params.add("name", name);
		if (kind == OrgKind.LOCATIONS) {
			params.add("addressLine1", "Plot C-14, MIDC Shiroli");
			params.add("city", "Kolhapur");
			params.add("state", "MH");
			params.add("pincode", "416122");
			params.add("ptApplicable", "true");
			params.add("_ptApplicable", "on");
		}
		return params;
	}

	private long create(OrgKind kind, String code, String name, String... extra) throws Exception {
		LinkedMultiValueMap<String, String> params = params(kind, code, name);
		for (int i = 0; i < extra.length; i += 2) {
			params.add(extra[i], extra[i + 1]);
		}
		mvc.perform(post("/hr/org/" + kind.slug()).with(hr()).with(csrf()).params(params))
			.andExpect(redirectedUrl("/hr/org/" + kind.slug()));
		return id(kind, code);
	}

	private long id(OrgKind kind, String code) {
		return jdbc.queryForObject("select id from " + TABLES.get(kind) + " where code = ?", Long.class, code);
	}

	private long count(OrgKind kind) {
		entityManager.flush();
		return jdbc.queryForObject("select count(*) from " + TABLES.get(kind), Long.class);
	}

	private User person(String email, String name, Role... roles) {
		return users.save(new User(email, name, EnumSet.of(roles[0], roles)));
	}

	private String page(org.springframework.test.web.servlet.RequestBuilder request, String view) throws Exception {
		return mvc.perform(request)
			.andExpect(status().isOk())
			.andExpect(view().name(view))
			.andReturn()
			.getResponse()
			.getContentAsString();
	}

	private List<AuditEvent> events(AuditAction action, String type, long id) {
		return events.findAll()
			.stream()
			.filter(event -> event.getAction() == action && type.equals(event.getEntityType())
					&& Long.valueOf(id).equals(event.getEntityId()))
			.toList();
	}

	private AuditEvent only(AuditAction action, String type, long id) {
		List<AuditEvent> found = events(action, type, id);
		assertThat(found).hasSize(1);
		return found.getFirst();
	}

	private static void assertRefused(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String field) {
		assertThatThrownBy(call).isInstanceOfSatisfying(BusinessRuleException.class,
				refused -> assertThat(refused.getField()).isEqualTo(field));
	}

}
