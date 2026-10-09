package com.kosh.audit;

import static com.kosh.audit.AuditChanges.cut;

import java.io.IOException;
import java.io.Writer;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.kosh.common.Csv;
import com.kosh.common.Fmt;
import com.kosh.security.KoshUserDetails;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The audit log. Any service records what it changed with {@link #record} (the signed-in user is the actor) or
 * {@link #recordAs} (sign-ins, links used before signing in, startup work). Events join the caller's transaction, so a
 * change that rolls back leaves no event, and an event that cannot be saved stops the change. Never record inside a
 * read-only transaction (MySQL refuses the insert, H2 does not): that throws on every database, so an export records
 * outside one, as {@link #export} does. The client's IP comes
 * from the current request (none outside one). Reading and exporting the log is for ADMIN users only.
 */
@Service
public class AuditService {

	/** Who did it: a user's e-mail and name at the time, or {@link #SYSTEM} for Kosh itself. */
	public record Actor(String email, String name) {

		public static final Actor SYSTEM = new Actor(null, "System");

		public Actor {
			email = (email == null || email.isBlank()) ? null : cut(email.strip().toLowerCase(Locale.ROOT), 254);
			name = (name == null || name.isBlank()) ? "Unknown" : cut(name.strip(), 120);
		}

	}

	/** One changed field of an event; null where the field was empty. */
	public record Change(String field, String before, String after) {

	}

	/** An event as the audit log page shows it. */
	public record Row(long id, LocalDateTime occurredAt, String actorName, String actorEmail, AuditAction action,
			String entityType, Long entityId, String summary, List<Change> changes, String ipAddress) {

	}

	/** Each of before/after JSON fits its column; fields that do not fit are left out and counted. */
	static final int MAX_JSON = 4000;

	static final int EXPORT_BATCH = 500;

	/** Replaces the fields left out of an oversized change. */
	static final String MORE_FIELDS = "…";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final TypeReference<LinkedHashMap<String, String>> FIELDS = new TypeReference<>() {
	};

	private static final DateTimeFormatter CSV_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private final AuditEventRepository events;

	private final Fmt fmt;

	private final Clock clock;

	public AuditService(AuditEventRepository events, Fmt fmt, Clock clock) {
		this.events = events;
		this.fmt = fmt;
		this.clock = clock;
	}

	/** Records an event by the signed-in user (System when nobody is signed in) that changed no fields. */
	@Transactional
	public void record(AuditAction action, String entityType, Long entityId, String summary) {
		record(action, entityType, entityId, summary, null);
	}

	/**
	 * Records an event by the signed-in user (System when nobody is signed in).
	 * @param entityType the kind of record as people read it ("User", "Payroll run"), or null
	 * @param changes the changed fields, or null
	 */
	@Transactional
	public void record(AuditAction action, String entityType, Long entityId, String summary, AuditChanges changes) {
		save(currentActor(), action, entityType, entityId, summary, changes);
	}

	/** Records an event by the given actor, for work done before or without a sign-in. {@code changes} may be null. */
	@Transactional
	public void recordAs(Actor actor, AuditAction action, String entityType, Long entityId, String summary,
			AuditChanges changes) {
		save(actor, action, entityType, entityId, summary, changes);
	}

	/** One page of matching events, newest first. */
	@PreAuthorize("hasRole('ADMIN')")
	@Transactional(readOnly = true)
	public Page<Row> list(AuditFilter filter, Pageable pageable) {
		Pageable newestFirst = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
				AuditEventRepository.NEWEST_FIRST);
		return events.findAll(AuditEventRepository.matching(filter, null), newestFirst).map(AuditService::row);
	}

	/** The kinds of record that have events, for the filter. */
	@PreAuthorize("hasRole('ADMIN')")
	@Transactional(readOnly = true)
	public List<String> entityTypes() {
		return events.findEntityTypes();
	}

	/**
	 * Writes the matching events as CSV, newest first, in batches so memory stays flat. The export is itself recorded
	 * first (and committed, outside a caller's transaction), so a download cut short is still on record; the rows
	 * written are the ones older than that event. Not one transaction: each batch reads on its own.
	 */
	@PreAuthorize("hasRole('ADMIN')")
	public void export(AuditFilter filter, Writer out) throws IOException {
		Long before = save(currentActor(), AuditAction.AUDIT_EXPORTED, null, null,
				"Exported the audit log: " + filter.describe(fmt), null).getId();
		out.write(Csv.BOM);
		out.write(Csv.line("Time (IST)", "Actor", "Actor email", "Action", "Record", "Record ID", "Summary", "Changes",
				"IP address"));
		List<AuditEvent> batch;
		do {
			batch = events.findBy(AuditEventRepository.matching(filter, before),
					query -> query.sortBy(AuditEventRepository.NEWEST_FIRST).limit(EXPORT_BATCH).all());
			for (AuditEvent event : batch) {
				out.write(Csv.line(event.getOccurredAt().format(CSV_TIME), event.getActorName(), event.getActorEmail(),
						event.getAction().label(), event.getEntityType(), event.getEntityId(), event.getSummary(),
						changesText(event), event.getIpAddress()));
				before = event.getId();
			}
		}
		while (batch.size() == EXPORT_BATCH);
	}

	private AuditEvent save(Actor actor, AuditAction action, String entityType, Long entityId, String summary,
			AuditChanges changes) {
		if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
			throw new IllegalStateException("Audit events cannot be recorded inside a read-only transaction");
		}
		String[] json = json(changes);
		LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
		return events.save(new AuditEvent(now, actor.email(), actor.name(), action, entityType, entityId,
				cut(summary, 300), json[0], json[1], clientIp()));
	}

	/** The signed-in user, or System for startup work and anonymous requests. */
	static Actor currentActor() {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
			return Actor.SYSTEM;
		}
		return new Actor(auth.getName(), KoshUserDetails.displayName(auth));
	}

	/**
	 * Before and after as JSON objects of the same fields. ponytail: values are capped at 200 characters, so only
	 * many fields full of escaped characters could overflow a column; then trailing fields are dropped and counted.
	 */
	static String[] json(AuditChanges changes) {
		if (changes == null || changes.isEmpty()) {
			return new String[2];
		}
		List<String> kept = new ArrayList<>(changes.before().keySet());
		while (true) {
			int left = changes.before().size() - kept.size();
			String before = json(changes.before(), kept, left);
			String after = json(changes.after(), kept, left);
			if (kept.size() == 1 || (before.length() <= MAX_JSON && after.length() <= MAX_JSON)) {
				return new String[] { before, after };
			}
			kept.removeLast();
		}
	}

	private static String json(Map<String, String> values, List<String> fields, int left) {
		Map<String, String> kept = new LinkedHashMap<>();
		fields.forEach(field -> kept.put(field, values.get(field)));
		if (left > 0) {
			kept.put(MORE_FIELDS, left + ((left == 1) ? " more field not kept" : " more fields not kept"));
		}
		return JSON.writeValueAsString(kept);
	}

	private static Row row(AuditEvent event) {
		return new Row(event.getId(), event.getOccurredAt(), event.getActorName(), event.getActorEmail(),
				event.getAction(), event.getEntityType(), event.getEntityId(), event.getSummary(), changes(event),
				event.getIpAddress());
	}

	static List<Change> changes(AuditEvent event) {
		Map<String, String> before = parse(event.getBeforeJson());
		Map<String, String> after = parse(event.getAfterJson());
		Set<String> fields = new LinkedHashSet<>(before.keySet());
		fields.addAll(after.keySet());
		return fields.stream().map(field -> new Change(field, before.get(field), after.get(field))).toList();
	}

	/** "Roles: Employee → Employee, HR; Name: — → Meera Deshpande" for the CSV. */
	private static String changesText(AuditEvent event) {
		return changes(event).stream()
			.map(change -> change.field() + ": " + orDash(change.before()) + " → " + orDash(change.after()))
			.collect(Collectors.joining("; "));
	}

	private static Map<String, String> parse(String json) {
		return (json == null) ? Map.of() : JSON.readValue(json, FIELDS);
	}

	private static String orDash(String value) {
		return (value == null || value.isEmpty()) ? Fmt.NONE : value;
	}

	private static String clientIp() {
		if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes request) {
			String ip = request.getRequest().getRemoteAddr();
			return (ip == null) ? null : cut(ip, 45);
		}
		return null;
	}

}
