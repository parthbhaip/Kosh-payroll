package com.kosh.audit;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import com.kosh.common.Masking;

/**
 * The fields an audited change touched, before and after, in the order they were added. A field whose value did not
 * change is left out. PAN and bank account numbers go through {@link #pan} and {@link #account}, which store only the
 * masks; passwords, tokens and Aadhaar numbers are never added at all. Field names are labels as people read them
 * ("Roles", "Basic salary"). Values are kept as text, at most {@value #MAX_VALUE} characters each.
 * <pre>AuditChanges.create().field("Name", oldName, newName).pan("PAN", oldPan, newPan)</pre>
 */
public final class AuditChanges {

	static final int MAX_VALUE = 200;

	static final int MAX_FIELD = 60;

	private final Map<String, String> before = new LinkedHashMap<>();

	private final Map<String, String> after = new LinkedHashMap<>();

	private AuditChanges() {
	}

	public static AuditChanges create() {
		return new AuditChanges();
	}

	/**
	 * Adds the field when its value changed, compared as the full text (only the stored values are cut). Null stays
	 * empty, {@link BigDecimal} is written plain ({@code 1500.00}), a collection as its items joined by commas (pass them
	 * in a stable order), anything else by {@code toString()}.
	 */
	public AuditChanges field(String name, Object oldValue, Object newValue) {
		String was = text(oldValue);
		String now = text(newValue);
		if (!Objects.equals(was, now)) {
			put(name, was, now);
		}
		return this;
	}

	/** A PAN: compared in full, stored masked ({@code ABXXXXX4F}). */
	public AuditChanges pan(String name, String oldValue, String newValue) {
		if (!Objects.equals(oldValue, newValue)) {
			put(name, Masking.pan(oldValue), Masking.pan(newValue));
		}
		return this;
	}

	/** A bank account number: compared in full, stored masked ({@code XXXXXX4321}). */
	public AuditChanges account(String name, String oldValue, String newValue) {
		if (!Objects.equals(oldValue, newValue)) {
			put(name, Masking.account(oldValue), Masking.account(newValue));
		}
		return this;
	}

	public boolean isEmpty() {
		return before.isEmpty();
	}

	/** Changed fields and their old values (null for empty), in the order added. */
	public Map<String, String> before() {
		return Collections.unmodifiableMap(before);
	}

	/** The same fields and their new values. */
	public Map<String, String> after() {
		return Collections.unmodifiableMap(after);
	}

	private void put(String name, String was, String now) {
		String field = cut(Objects.requireNonNull(name, "name"), MAX_FIELD);
		before.put(field, (was == null) ? null : cut(was, MAX_VALUE));
		after.put(field, (now == null) ? null : cut(now, MAX_VALUE));
	}

	private static String text(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof BigDecimal amount) {
			return amount.toPlainString();
		}
		if (value instanceof Collection<?> items) {
			return items.stream().map(String::valueOf).collect(Collectors.joining(", "));
		}
		return value.toString();
	}

	/** The text, or its first {@code max - 1} characters and an ellipsis. Also used by {@link AuditService}. */
	static String cut(String text, int max) {
		return (text.length() <= max) ? text : text.substring(0, max - 1) + "…";
	}

}
