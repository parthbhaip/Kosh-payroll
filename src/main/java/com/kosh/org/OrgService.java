package com.kosh.org;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jakarta.validation.groups.Default;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kosh.audit.AuditAction;
import com.kosh.audit.AuditChanges;
import com.kosh.audit.AuditService;
import com.kosh.common.BusinessRuleException;
import com.kosh.common.NotFoundException;
import com.kosh.user.Role;
import com.kosh.user.User;
import com.kosh.user.UserRepository;

/**
 * The organisation structure (PLAN T12): departments, designations, grades, cost centres and work locations. HR and
 * ADMIN add and change them; codes and names are unique within each kind. An item is deleted only while nothing
 * references it: the foreign keys of later tables (employees and the rest) refuse the delete, and HR deactivates it
 * instead. Every change goes in the audit log with the kind's label as the entity type.
 */
@Service
@PreAuthorize("hasAnyRole('HR', 'ADMIN')")
public class OrgService {

	/** A line in a list. {@code head}: departments only; {@code place} and {@code ptApplicable}: work locations only. */
	public record Row(long id, String code, String name, boolean active, String head, String place,
			boolean ptApplicable) {

	}

	/** One item for its edit page. */
	public record Item(long id, String name, boolean active, OrgDetails details) {

	}

	/** A rule {@link OrgDetails} breaks, for one form field. */
	public record Problem(String field, String message) {

	}

	/** The saved item's name and the labels of the fields that changed (empty when nothing did). */
	public record Saved(String name, List<String> changed) {

	}

	static final String HEAD_NOT_FOUND = "Choose someone from the list.";

	static final String HEAD_NOT_ELIGIBLE = "Choose someone whose account is enabled and has the Employee role.";

	/** The form's fields in page order, so problems are listed (and the first one thrown) in a stable order. */
	private static final List<String> FIELDS = List.of("name", "code", "headUserId", "addressLine1", "addressLine2",
			"city", "state", "pincode");

	private final Map<OrgKind, OrgItemRepository<? extends OrgItem>> repositories = new EnumMap<>(OrgKind.class);

	private final DepartmentRepository departments;

	private final UserRepository users;

	private final AuditService audit;

	private final Validator validator;

	public OrgService(DepartmentRepository departments, DesignationRepository designations, GradeRepository grades,
			CostCentreRepository costCentres, WorkLocationRepository locations, UserRepository users,
			AuditService audit, Validator validator) {
		repositories.put(OrgKind.DEPARTMENTS, departments);
		repositories.put(OrgKind.DESIGNATIONS, designations);
		repositories.put(OrgKind.GRADES, grades);
		repositories.put(OrgKind.COST_CENTRES, costCentres);
		repositories.put(OrgKind.LOCATIONS, locations);
		this.departments = departments;
		this.users = users;
		this.audit = audit;
		this.validator = validator;
	}

	/** Every item of the kind, active or not. Department heads come in the same query. */
	@Transactional(readOnly = true)
	public List<Row> list(OrgKind kind, Sort sort) {
		return repository(kind).findAll(sort).stream().map(OrgService::row).toList();
	}

	/** How many items each kind has, for the tabs. */
	@Transactional(readOnly = true)
	public Map<OrgKind, Long> counts() {
		Map<OrgKind, Long> counts = new EnumMap<>(OrgKind.class);
		repositories.forEach((kind, repository) -> counts.put(kind, repository.count()));
		return counts;
	}

	@Transactional(readOnly = true)
	public Item item(OrgKind kind, long id) {
		OrgItem item = find(kind, id);
		return new Item(item.getId(), item.getName(), item.isActive(), details(item));
	}

	/**
	 * Who may head a department, id to "Name (email)": enabled accounts with the Employee role, by name, and the current
	 * head ({@code currentHeadId}, may be null) even when that account no longer qualifies.
	 */
	@Transactional(readOnly = true)
	public Map<Long, String> headOptions(Long currentHeadId) {
		Map<Long, String> options = new LinkedHashMap<>();
		for (User user : users.findEnabledEmployees()) {
			options.put(user.getId(), headLabel(user));
		}
		if (currentHeadId != null && !options.containsKey(currentHeadId)) {
			users.findById(currentHeadId).ifPresent(user -> options.put(user.getId(), headLabel(user)));
		}
		return options;
	}

	/**
	 * Every rule {@code details} breaks for an item of this kind, in form order: the field rules on {@link OrgDetails},
	 * a code or name another item of the kind already has, and the department head. {@code id} is the item being
	 * changed, null for a new one.
	 */
	@Transactional(readOnly = true)
	public List<Problem> problems(OrgKind kind, Long id, OrgDetails details) {
		List<Problem> problems = new ArrayList<>();
		Class<?>[] groups = (kind == OrgKind.LOCATIONS) ? new Class<?>[] { Default.class, OrgDetails.Location.class }
				: new Class<?>[] { Default.class };
		for (ConstraintViolation<OrgDetails> violation : validator.validate(details, groups)) {
			problems.add(new Problem(violation.getPropertyPath().toString(), violation.getMessage()));
		}
		OrgItemRepository<? extends OrgItem> repository = repository(kind);
		if (details.code() != null) {
			repository.findByCode(details.code())
				.filter(other -> !other.getId().equals(id))
				.ifPresent(other -> problems.add(new Problem("code", taken(kind, other, "uses this code"))));
		}
		if (details.name() != null) {
			repository.findFirstByNameIgnoreCase(details.name())
				.filter(other -> !other.getId().equals(id))
				.ifPresent(other -> problems.add(new Problem("name", taken(kind, other, "has this name"))));
		}
		if (kind == OrgKind.DEPARTMENTS && details.headUserId() != null) {
			User head = users.findById(details.headUserId()).orElse(null);
			if (head == null) {
				problems.add(new Problem("headUserId", HEAD_NOT_FOUND));
			}
			else if (!(head.isEnabled() && head.hasRole(Role.EMPLOYEE)) && !isCurrentHead(id, head)) {
				problems.add(new Problem("headUserId", HEAD_NOT_ELIGIBLE));
			}
		}
		problems.sort(Comparator.comparingInt(problem -> FIELDS.indexOf(problem.field())));
		return problems;
	}

	/** @return the new item's id */
	@Transactional
	public long create(OrgKind kind, OrgDetails details) {
		requireValid(kind, null, details);
		OrgItem item = kind.newItem();
		apply(item, details);
		save(kind, item);
		AuditChanges changes = AuditChanges.create();
		values(item).forEach((field, value) -> changes.field(field, null, value));
		audit.record(AuditAction.ORG_ITEM_ADDED, kind.label(), item.getId(),
				"Added %s %s (%s)".formatted(kind.noun(), item.getName(), item.getCode()), changes);
		return item.getId();
	}

	@Transactional
	public Saved update(OrgKind kind, long id, OrgDetails details) {
		OrgItem item = find(kind, id);
		requireValid(kind, id, details);
		Map<String, Object> before = values(item);
		apply(item, details);
		save(kind, item);
		AuditChanges changes = AuditChanges.create();
		values(item).forEach((field, value) -> changes.field(field, before.get(field), value));
		List<String> changed = List.copyOf(changes.before().keySet());
		if (!changed.isEmpty()) {
			audit.record(AuditAction.ORG_ITEM_CHANGED, kind.label(), id,
					"Changed %s %s: %s".formatted(kind.noun(), item.getName(), String.join(", ", changed)), changes);
		}
		return new Saved(item.getName(), changed);
	}

	/** Activates or deactivates the item; recorded only when that changes something. @return the item's name */
	@Transactional
	public String setActive(OrgKind kind, long id, boolean active) {
		OrgItem item = find(kind, id);
		if (item.isActive() != active) {
			item.setActive(active);
			audit.record(active ? AuditAction.ORG_ITEM_ACTIVATED : AuditAction.ORG_ITEM_DEACTIVATED, kind.label(), id,
					"%s %s %s".formatted(active ? "Activated" : "Deactivated", kind.noun(), item.getName()));
		}
		return item.getName();
	}

	/**
	 * Deletes an item nothing references. Any table pointing at it through a foreign key makes the delete fail, which
	 * is refused here with a message saying it is in use. @return the deleted item's name
	 */
	@Transactional
	public String delete(OrgKind kind, long id) {
		OrgItem item = find(kind, id);
		Map<String, Object> before = values(item);
		OrgItemRepository<OrgItem> repository = typed(kind);
		try {
			repository.delete(item);
			repository.flush();
		}
		catch (DataIntegrityViolationException inUse) {
			throw new BusinessRuleException(null, item.getName() + " is in use, so it can’t be deleted");
		}
		AuditChanges changes = AuditChanges.create();
		before.forEach((field, value) -> changes.field(field, value, null));
		audit.record(AuditAction.ORG_ITEM_DELETED, kind.label(), id,
				"Deleted %s %s (%s)".formatted(kind.noun(), item.getName(), item.getCode()), changes);
		return item.getName();
	}

	private void requireValid(OrgKind kind, Long id, OrgDetails details) {
		List<Problem> problems = problems(kind, id, details);
		if (!problems.isEmpty()) {
			throw new BusinessRuleException(problems.getFirst().field(), problems.getFirst().message());
		}
	}

	private void apply(OrgItem item, OrgDetails details) {
		item.setCode(details.code());
		item.setName(details.name());
		if (item instanceof Department department) {
			department.setHead((details.headUserId() == null) ? null
					: users.findById(details.headUserId()).orElseThrow(() -> new NotFoundException("No such user")));
		}
		if (item instanceof WorkLocation location) {
			location.setAddress(details.addressLine1(), details.addressLine2(), details.city(), details.state(),
					details.pincode());
			location.setPtApplicable(details.ptApplicable());
		}
	}

	/** Inserts or updates at once, so a code or name saved by someone else a moment ago fails here, not at commit. */
	private void save(OrgKind kind, OrgItem item) {
		try {
			typed(kind).saveAndFlush(item);
		}
		catch (DataIntegrityViolationException race) {
			throw new BusinessRuleException(null, "Another %s with this code or name was saved a moment ago. Check the list and try again."
				.formatted(kind.noun()));
		}
	}

	private boolean isCurrentHead(Long departmentId, User head) {
		return departmentId != null && departments.findById(departmentId)
			.map(Department::getHead)
			.map(current -> current.getId().equals(head.getId()))
			.orElse(false);
	}

	private OrgItem find(OrgKind kind, long id) {
		return repository(kind).findById(id)
			.orElseThrow(() -> new NotFoundException("No " + kind.noun() + " with id " + id));
	}

	private OrgItemRepository<? extends OrgItem> repository(OrgKind kind) {
		return repositories.get(kind);
	}

	@SuppressWarnings("unchecked")
	private OrgItemRepository<OrgItem> typed(OrgKind kind) {
		return (OrgItemRepository<OrgItem>) repositories.get(kind);
	}

	private static String taken(OrgKind kind, OrgItem other, String what) {
		return other.isActive() ? "Another %s already %s.".formatted(kind.noun(), what)
				: "An inactive %s, %s, already %s. Activate it instead.".formatted(kind.noun(), other.getName(), what);
	}

	private static String headLabel(User user) {
		return "%s (%s)".formatted(user.getDisplayName(), user.getEmail());
	}

	private static Row row(OrgItem item) {
		String head = (item instanceof Department department && department.getHead() != null)
				? department.getHead().getDisplayName() : null;
		if (item instanceof WorkLocation location) {
			return new Row(item.getId(), item.getCode(), item.getName(), item.isActive(), null,
					location.getCity() + ", " + location.getState().label(), location.isPtApplicable());
		}
		return new Row(item.getId(), item.getCode(), item.getName(), item.isActive(), head, null, false);
	}

	private static OrgDetails details(OrgItem item) {
		if (item instanceof WorkLocation location) {
			return new OrgDetails(item.getCode(), item.getName(), null, location.getAddressLine1(),
					location.getAddressLine2(), location.getCity(), location.getState(), location.getPincode(),
					location.isPtApplicable());
		}
		Long head = (item instanceof Department department && department.getHead() != null)
				? department.getHead().getId() : null;
		return new OrgDetails(item.getCode(), item.getName(), head, null, null, null, null, null, false);
	}

	/** The audited fields, labelled as on the form. */
	private static Map<String, Object> values(OrgItem item) {
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("Code", item.getCode());
		values.put("Name", item.getName());
		if (item instanceof Department department) {
			values.put("Head", (department.getHead() == null) ? null : headLabel(department.getHead()));
		}
		if (item instanceof WorkLocation location) {
			values.put("Address line 1", location.getAddressLine1());
			values.put("Address line 2", location.getAddressLine2());
			values.put("City", location.getCity());
			values.put("State", (location.getState() == null) ? null : location.getState().label());
			values.put("PIN code", location.getPincode());
			values.put("Professional tax", location.isPtApplicable() ? "Deducted" : "Not deducted");
		}
		return values;
	}

}
