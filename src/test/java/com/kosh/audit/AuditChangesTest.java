package com.kosh.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

/** The changed-field builder and how its fields become the before/after JSON of an event. */
class AuditChangesTest {

	@Test
	void onlyFieldsWhoseValueChangedAreKeptInTheOrderAdded() {
		AuditChanges changes = AuditChanges.create()
			.field("Name", "Meera Deshpande", "Meera Deshpande")
			.field("Roles", List.of("HR", "Employee"), List.of("Payroll", "Employee"))
			.field("Basic salary", new BigDecimal("25000.00"), new BigDecimal("27500.50"))
			.field("Exit date", null, "2026-10-31")
			.field("Amount", new BigDecimal("1E+3"), new BigDecimal("1000"));

		assertThat(changes.before()).containsExactly(entry("Roles", "HR, Employee"), entry("Basic salary", "25000.00"),
				entry("Exit date", null));
		assertThat(changes.after()).containsExactly(entry("Roles", "Payroll, Employee"),
				entry("Basic salary", "27500.50"), entry("Exit date", "2026-10-31"));
		assertThat(AuditChanges.create().field("Name", "A", "A").isEmpty()).isTrue();
	}

	@Test
	void panAndBankAccountAreComparedInFullButKeptOnlyMasked() {
		AuditChanges changes = AuditChanges.create()
			.pan("PAN", "ABCDE1234F", "ABCDE9876G")
			.account("Bank account", "001234567894321", "009876543210987")
			.pan("Same PAN", "ABCDE1234F", "ABCDE1234F")
			.account("New account", null, "50100123456789");

		assertThat(changes.before()).containsExactly(entry("PAN", "ABXXXXX4F"), entry("Bank account", "XXXXXX4321"),
				entry("New account", null));
		assertThat(changes.after()).containsExactly(entry("PAN", "ABXXXXX6G"), entry("Bank account", "XXXXXX0987"),
				entry("New account", "XXXXXX6789"));
		String[] json = AuditService.json(changes);
		assertThat(json[0] + json[1]).doesNotContain("ABCDE1234F", "ABCDE9876G", "001234567894321", "009876543210987",
				"50100123456789");
	}

	@Test
	void longValuesAndFieldNamesAreCut() {
		AuditChanges changes = AuditChanges.create().field("F".repeat(80), "", "x".repeat(300));

		String field = changes.after().keySet().iterator().next();
		assertThat(field).hasSize(AuditChanges.MAX_FIELD).endsWith("…");
		assertThat(changes.after().get(field)).hasSize(AuditChanges.MAX_VALUE).endsWith("…");
	}

	@Test
	void beforeAndAfterBecomeJsonObjectsOfTheSameFields() {
		String[] json = AuditService.json(AuditChanges.create()
			.field("Roles", "HR, Employee", "Payroll, Employee")
			.field("Name", null, "Meera \"Mi\" Deshpande"));

		assertThat(json[0]).isEqualTo("{\"Roles\":\"HR, Employee\",\"Name\":null}");
		assertThat(json[1]).isEqualTo("{\"Roles\":\"Payroll, Employee\",\"Name\":\"Meera \\\"Mi\\\" Deshpande\"}");
		assertThat(AuditService.json(null)).containsExactly(null, null);
		assertThat(AuditService.json(AuditChanges.create())).containsExactly(null, null);
	}

	@Test
	void fieldsThatWouldOverflowTheColumnAreDroppedFromTheEndAndCounted() {
		AuditChanges changes = AuditChanges.create();
		for (int i = 1; i <= 30; i++) {
			changes.field("Field " + i, "old " + "\"".repeat(150), "new " + i);
		}

		String[] json = AuditService.json(changes);

		assertThat(json[0]).hasSizeLessThanOrEqualTo(AuditService.MAX_JSON).startsWith("{\"Field 1\":");
		assertThat(json[1]).hasSizeLessThanOrEqualTo(AuditService.MAX_JSON);
		// Each "old" value escapes to over 300 characters, so 12 fit and 18 are counted.
		assertThat(json[0]).contains("\"Field 12\":").doesNotContain("\"Field 13\":")
			.endsWith(",\"…\":\"18 more fields not kept\"}");
		assertThat(json[1]).contains("\"Field 12\":\"new 12\"").endsWith(",\"…\":\"18 more fields not kept\"}");
	}

}
