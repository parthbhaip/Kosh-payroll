package com.kosh.audit;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.kosh.common.Fmt;

/**
 * Which audit events to show or export. Every part is optional (null): {@code actor} is text found in the actor's name
 * or e-mail, {@code entityType}/{@code entityId} the record, and {@code from}/{@code to} an inclusive range of dates in
 * IST.
 */
public record AuditFilter(String actor, AuditAction action, String entityType, Long entityId, LocalDate from,
		LocalDate to) {

	public static final AuditFilter NONE = new AuditFilter(null, null, null, null, null, null);

	public boolean isEmpty() {
		return equals(NONE);
	}

	/** "Sign-in failed, actor “meera”, User 12, 1 Oct 2026 to 5 Oct 2026", or "all events". */
	String describe(Fmt fmt) {
		List<String> parts = new ArrayList<>();
		if (action != null) {
			parts.add(action.label());
		}
		if (actor != null) {
			parts.add("actor “" + actor + "”");
		}
		if (entityType != null || entityId != null) {
			parts.add(((entityType != null) ? entityType : "record") + ((entityId != null) ? " " + entityId : ""));
		}
		if (from != null && to != null) {
			parts.add(fmt.date(from) + " to " + fmt.date(to));
		}
		else if (from != null) {
			parts.add("from " + fmt.date(from));
		}
		else if (to != null) {
			parts.add("up to " + fmt.date(to));
		}
		return parts.isEmpty() ? "all events" : String.join(", ", parts);
	}

}
