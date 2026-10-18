package com.kosh.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.kosh.web.Flash;

/**
 * An item in use cannot be deleted (PLAN T12): HR is told to deactivate it instead, the item and its audit trail stay,
 * and deactivating still works. A probe table here points at all five through foreign keys, as the employees table does
 * since T15 ({@code EmployeeModelTest} checks the employees' own keys). Runs WITHOUT a test transaction, because the refused delete
 * must fail at the database for real; it drops the probe table and removes its items and their audit events afterwards.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrgInUseTest {

	/** The probe table's column pointing at each kind. */
	private static final Map<OrgKind, String> COLUMNS = Map.of(OrgKind.DEPARTMENTS, "department_id",
			OrgKind.DESIGNATIONS, "designation_id", OrgKind.GRADES, "grade_id", OrgKind.COST_CENTRES, "cost_centre_id",
			OrgKind.LOCATIONS, "work_location_id");

	private final MockMvc mvc;

	private final JdbcTemplate jdbc;

	@Autowired
	OrgInUseTest(MockMvc mvc, JdbcTemplate jdbc) {
		this.mvc = mvc;
		this.jdbc = jdbc;
	}

	@BeforeEach
	void createTheProbeTable() {
		jdbc.execute("""
				CREATE TABLE org_in_use_probe (
				    id               BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
				    department_id    BIGINT REFERENCES departments (id),
				    designation_id   BIGINT REFERENCES designations (id),
				    grade_id         BIGINT REFERENCES grades (id),
				    cost_centre_id   BIGINT REFERENCES cost_centres (id),
				    work_location_id BIGINT REFERENCES work_locations (id)
				)""");
	}

	@AfterEach
	void removeEverything() {
		jdbc.execute("DROP TABLE IF EXISTS org_in_use_probe");
		for (OrgKind kind : OrgKind.values()) {
			String table = OrgStructureTest.TABLES.get(kind);
			for (Long id : jdbc.queryForList("select id from " + table + " where code = 'USED-1'", Long.class)) {
				jdbc.update("delete from audit_events where entity_type = ? and entity_id = ?", kind.label(), id);
			}
			jdbc.update("delete from " + table + " where code = 'USED-1'");
		}
	}

	@ParameterizedTest
	@EnumSource(OrgKind.class)
	void anItemInUseIsNotDeletedAndHrIsToldToDeactivateIt(OrgKind kind) throws Exception {
		String base = "/hr/org/" + kind.slug();
		String name = "Used " + kind.noun();
		mvc.perform(post(base).with(OrgStructureTest.hr())
			.with(csrf())
			.params(OrgStructureTest.params(kind, "USED-1", name))).andExpect(redirectedUrl(base));
		long id = jdbc.queryForObject("select id from " + OrgStructureTest.TABLES.get(kind) + " where code = 'USED-1'",
				Long.class);
		jdbc.update("insert into org_in_use_probe (" + COLUMNS.get(kind) + ") values (?)", id);

		mvc.perform(post(base + "/{id}/delete", id).with(OrgStructureTest.hr()).with(csrf()))
			.andExpect(redirectedUrl(base + "/" + id))
			.andExpect(flash().attribute(Flash.ATTRIBUTE, Flash.danger(name + " is in use, so it can’t be deleted")
				.withText("Deactivate it instead: it stays on the records that have it but can’t be chosen for new ones.")));
		assertThat(jdbc.queryForObject("select count(*) from " + OrgStructureTest.TABLES.get(kind) + " where id = ?",
				Long.class, id)).isOne();
		assertThat(actions(kind, id)).containsExactly("ORG_ITEM_ADDED");
		mvc.perform(get(base + "/{id}", id).with(OrgStructureTest.hr())).andExpect(status().isOk());

		mvc.perform(post(base + "/{id}/deactivate", id).with(OrgStructureTest.hr()).with(csrf()))
			.andExpect(flash().attribute(Flash.ATTRIBUTE, Flash.success(name + " deactivated")
				.withText("It stays on the records that have it but can’t be chosen for new ones.")));
		assertThat(jdbc.queryForObject("select active from " + OrgStructureTest.TABLES.get(kind) + " where id = ?",
				Boolean.class, id)).isFalse();
		assertThat(actions(kind, id)).containsExactly("ORG_ITEM_ADDED", "ORG_ITEM_DEACTIVATED");
	}

	private List<String> actions(OrgKind kind, long id) {
		return jdbc.queryForList("select action from audit_events where entity_type = ? and entity_id = ? order by id",
				String.class, kind.label(), id);
	}

}
