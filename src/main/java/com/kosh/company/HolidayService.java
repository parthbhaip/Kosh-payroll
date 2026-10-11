package com.kosh.company;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kosh.audit.AuditAction;
import com.kosh.audit.AuditChanges;
import com.kosh.audit.AuditService;
import com.kosh.common.BusinessRuleException;
import com.kosh.common.Fmt;
import com.kosh.common.NotFoundException;
import com.kosh.common.Text;
import com.kosh.org.OrgKind;
import com.kosh.org.WorkLocation;
import com.kosh.org.WorkLocationRepository;

/**
 * The holiday calendar and work week of each work location (PLAN T13). Any feature asks {@link #isWorkingDay} and
 * {@link #workingDaysBetween} (leave, attendance, proration, full and final); HR and ADMIN keep the lists. A working
 * day is a day that is neither a weekly off ({@link WeeklyOffs}) nor a national or festival holiday; optional holidays
 * are working days. Every change goes in the audit log.
 */
@Service
public class HolidayService {

	/** Holidays can be kept for these calendar years. */
	public static final int FIRST_YEAR = 2000;

	public static final int LAST_YEAR = 2099;

	private static final String HR_OR_ADMIN = "hasAnyRole('HR', 'ADMIN')";

	private static final String DATE_CONFIRMED = "Date confirmed";

	/** The form's fields in page order, so problems are listed (and the first one thrown) in a stable order. */
	private static final List<String> FIELDS = List.of("date", "name", "type");

	/** What HR enters for a holiday; the name is tidied on construction. */
	public record Details(
			@NotNull(message = "Enter the date.")
			LocalDate date,

			@NotBlank(message = "Enter the holiday’s name.")
			@Size(max = Holiday.MAX_NAME, message = "Use at most " + Holiday.MAX_NAME + " characters.")
			String name,

			@NotNull(message = "Choose the type.")
			HolidayType type) {

		public Details {
			name = Text.clean(name);
		}

	}

	public record LocationRef(long id, String name, boolean active) {

	}

	/** A holiday in a year's list. {@code onWeeklyOff}: the date is a weekly off anyway. */
	public record Row(long id, LocalDate date, String name, HolidayType type, boolean dateToCheck,
			boolean onWeeklyOff) {

		/** {@code Monday}. */
		public String weekday() {
			return WeeklyOffs.name(date.getDayOfWeek());
		}

	}

	/** One location's year: its holidays by date, its weekly offs and how many working days they leave. */
	public record YearView(LocationRef location, int year, List<Row> holidays, WeeklyOffs weeklyOffs, int workingDays,
			long previousYearCount) {

		public long closedCount() {
			return holidays.stream().filter(row -> row.type().closes()).count();
		}

		public long optionalCount() {
			return holidays.size() - closedCount();
		}

		/** "12 holidays and 2 optional · 289 working days". */
		public String summary() {
			long closed = closedCount();
			String holidays = (closed == 0) ? "No holidays" : closed + ((closed == 1) ? " holiday" : " holidays");
			String optional = (optionalCount() == 0) ? "" : " and " + optionalCount() + " optional";
			return holidays + optional + " · " + workingDays + " working days";
		}

		/** Each day of the week, Monday first, with what it is: "Working day", "Off on the 2nd and 4th". */
		public Map<String, String> week() {
			Map<String, String> week = new LinkedHashMap<>();
			for (DayOfWeek day : DayOfWeek.values()) {
				week.put(WeeklyOffs.name(day), weeklyOffs.describe(day));
			}
			return week;
		}

		public long toCheckCount() {
			return holidays.stream().filter(Row::dateToCheck).count();
		}

	}

	/** One holiday for its edit page. */
	public record Item(long id, LocationRef location, Details details, boolean dateToCheck) {

	}

	/** A rule {@link Details} breaks, for one form field. */
	public record Problem(String field, String message) {

	}

	/** A saved or deleted holiday, with the labels of the fields that changed (empty when nothing did). */
	public record Saved(long locationId, String name, int year, List<String> changed) {

		/** Only the copied date was confirmed; nothing else changed. */
		public boolean onlyConfirmed() {
			return changed.equals(List.of(DATE_CONFIRMED));
		}

	}

	public record Copied(int copied, int skipped) {

	}

	private final HolidayRepository holidays;

	private final WorkWeekRepository workWeeks;

	private final WorkLocationRepository locations;

	private final AuditService audit;

	private final Validator validator;

	private final Fmt fmt;

	HolidayService(HolidayRepository holidays, WorkWeekRepository workWeeks, WorkLocationRepository locations,
			AuditService audit, Validator validator, Fmt fmt) {
		this.holidays = holidays;
		this.workWeeks = workWeeks;
		this.locations = locations;
		this.audit = audit;
		this.validator = validator;
		this.fmt = fmt;
	}

	// ------------------------------------------------------------------ for every feature

	/** Whether people at the location work on {@code date}. */
	@Transactional(readOnly = true)
	public boolean isWorkingDay(long locationId, LocalDate date) {
		return workingDaysBetween(locationId, date, date) == 1;
	}

	/** The working days at the location from {@code from} to {@code to}, both included. */
	@Transactional(readOnly = true)
	public int workingDaysBetween(long locationId, LocalDate from, LocalDate to) {
		if (to.isBefore(from)) {
			throw new IllegalArgumentException("The range ends before it starts: " + from + " to " + to);
		}
		WeeklyOffs offs = weeklyOffs(location(locationId));
		return workingDays(offs, new HashSet<>(holidays.closedDays(locationId, from, to)), from, to);
	}

	/** Days from {@code from} to {@code to} (both included) that are neither weekly offs nor in {@code closed}. */
	static int workingDays(WeeklyOffs offs, Set<LocalDate> closed, LocalDate from, LocalDate to) {
		return (int) from.datesUntil(to.plusDays(1)).filter(day -> !offs.isOff(day) && !closed.contains(day)).count();
	}

	// ------------------------------------------------------------------ HR and ADMIN

	/** Every work location, active or not, by name. */
	@PreAuthorize(HR_OR_ADMIN)
	@Transactional(readOnly = true)
	public List<LocationRef> locations() {
		return locations.findAll(Sort.by(Sort.Order.asc("name").ignoreCase())).stream().map(HolidayService::ref).toList();
	}

	@PreAuthorize(HR_OR_ADMIN)
	@Transactional(readOnly = true)
	public LocationRef locationRef(long locationId) {
		return ref(location(locationId));
	}

	@PreAuthorize(HR_OR_ADMIN)
	@Transactional(readOnly = true)
	public WeeklyOffs workWeek(long locationId) {
		return weeklyOffs(location(locationId));
	}

	@PreAuthorize(HR_OR_ADMIN)
	@Transactional(readOnly = true)
	public YearView year(long locationId, int year) {
		WorkLocation location = location(locationId);
		LocalDate first = LocalDate.of(year, 1, 1);
		LocalDate last = LocalDate.of(year, 12, 31);
		WeeklyOffs offs = weeklyOffs(location);
		List<Holiday> list = holidays.findByLocationIdAndDateBetweenOrderByDate(locationId, first, last);
		Set<LocalDate> closed = list.stream()
			.filter(holiday -> holiday.getType().closes())
			.map(Holiday::getDate)
			.collect(Collectors.toSet());
		List<Row> rows = list.stream()
			.map(holiday -> new Row(holiday.getId(), holiday.getDate(), holiday.getName(), holiday.getType(),
					holiday.isDateToCheck(), offs.isOff(holiday.getDate())))
			.toList();
		long previous = holidays.countByLocationIdAndDateBetween(locationId, first.minusYears(1), last.minusYears(1));
		return new YearView(ref(location), year, rows, offs, workingDays(offs, closed, first, last), previous);
	}

	@PreAuthorize(HR_OR_ADMIN)
	@Transactional(readOnly = true)
	public Item item(long id) {
		Holiday holiday = find(id);
		return new Item(id, ref(holiday.getLocation()),
				new Details(holiday.getDate(), holiday.getName(), holiday.getType()), holiday.isDateToCheck());
	}

	/**
	 * Every rule {@code details} breaks, in form order: the field rules on {@link Details}, a date outside
	 * {@link #FIRST_YEAR} to {@link #LAST_YEAR}, and a date that already has a holiday at the location. {@code id} is the
	 * holiday being changed, null for a new one.
	 */
	@PreAuthorize(HR_OR_ADMIN)
	@Transactional(readOnly = true)
	public List<Problem> problems(long locationId, Long id, Details details) {
		List<Problem> problems = new ArrayList<>();
		for (ConstraintViolation<Details> violation : validator.validate(details)) {
			problems.add(new Problem(violation.getPropertyPath().toString(), violation.getMessage()));
		}
		LocalDate date = details.date();
		if (date != null && (date.getYear() < FIRST_YEAR || date.getYear() > LAST_YEAR)) {
			problems.add(new Problem("date", "Enter a date from %d to %d.".formatted(FIRST_YEAR, LAST_YEAR)));
		}
		else if (date != null) {
			holidays.findByLocationIdAndDate(locationId, date)
				.filter(other -> !other.getId().equals(id))
				.ifPresent(other -> problems.add(new Problem("date", other.getName() + " is already on this date.")));
		}
		problems.sort(Comparator.comparingInt(problem -> FIELDS.indexOf(problem.field())));
		return problems;
	}

	/** @return the new holiday's id */
	@PreAuthorize(HR_OR_ADMIN)
	@Transactional
	public long create(long locationId, Details details) {
		WorkLocation location = location(locationId);
		requireValid(locationId, null, details);
		Holiday holiday = new Holiday(location, details.date(), details.name(), details.type(), false);
		save(holiday);
		AuditChanges changes = AuditChanges.create();
		values(holiday).forEach((field, value) -> changes.field(field, null, value));
		audit.record(AuditAction.HOLIDAY_ADDED, Holiday.AUDIT_TYPE, holiday.getId(), "Added holiday %s on %s at %s"
			.formatted(holiday.getName(), fmt.date(holiday.getDate()), location.getName()), changes);
		return holiday.getId();
	}

	/** Saves the holiday; this also confirms a date copied from the previous year. */
	@PreAuthorize(HR_OR_ADMIN)
	@Transactional
	public Saved update(long id, Details details) {
		Holiday holiday = find(id);
		WorkLocation location = holiday.getLocation();
		requireValid(location.getId(), id, details);
		Map<String, Object> before = values(holiday);
		boolean wasToCheck = holiday.isDateToCheck();
		holiday.set(details.date(), details.name(), details.type());
		save(holiday);
		AuditChanges changes = AuditChanges.create();
		values(holiday).forEach((field, value) -> changes.field(field, before.get(field), value));
		if (wasToCheck) {
			changes.field(DATE_CONFIRMED, "No", "Yes");
		}
		List<String> changed = List.copyOf(changes.before().keySet());
		if (!changed.isEmpty()) {
			audit.record(AuditAction.HOLIDAY_CHANGED, Holiday.AUDIT_TYPE, id, "Changed holiday %s at %s: %s"
				.formatted(holiday.getName(), location.getName(), String.join(", ", changed)), changes);
		}
		return new Saved(location.getId(), holiday.getName(), holiday.getDate().getYear(), changed);
	}

	@PreAuthorize(HR_OR_ADMIN)
	@Transactional
	public Saved delete(long id) {
		Holiday holiday = find(id);
		WorkLocation location = holiday.getLocation();
		AuditChanges changes = AuditChanges.create();
		values(holiday).forEach((field, value) -> changes.field(field, value, null));
		holidays.delete(holiday);
		audit.record(AuditAction.HOLIDAY_DELETED, Holiday.AUDIT_TYPE, id, "Deleted holiday %s on %s at %s"
			.formatted(holiday.getName(), fmt.date(holiday.getDate()), location.getName()), changes);
		return new Saved(location.getId(), holiday.getName(), holiday.getDate().getYear(), List.of());
	}

	/**
	 * Copies the location's holidays of the previous year into {@code year}, on the same day and month (29 February
	 * becomes the 28th). A holiday is skipped when {@code year} already has a holiday on that date or one with the same
	 * name. Festival and optional holidays are marked "date to check": their dates usually move, so HR confirms each.
	 */
	@PreAuthorize(HR_OR_ADMIN)
	@Transactional
	public Copied copyPreviousYear(long locationId, int year) {
		WorkLocation location = location(locationId);
		if (year < FIRST_YEAR || year > LAST_YEAR) {
			throw new BusinessRuleException(null, "Choose a year from %d to %d.".formatted(FIRST_YEAR, LAST_YEAR));
		}
		List<Holiday> source = holidays.findByLocationIdAndDateBetweenOrderByDate(locationId,
				LocalDate.of(year - 1, 1, 1), LocalDate.of(year - 1, 12, 31));
		if (source.isEmpty()) {
			throw new BusinessRuleException(null, "%s has no holidays in %d to copy.".formatted(location.getName(),
					year - 1));
		}
		List<Holiday> existing = holidays.findByLocationIdAndDateBetweenOrderByDate(locationId,
				LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
		Set<LocalDate> takenDates = existing.stream().map(Holiday::getDate).collect(Collectors.toSet());
		Set<String> takenNames = existing.stream().map(holiday -> lower(holiday.getName())).collect(Collectors.toSet());
		List<Holiday> copies = new ArrayList<>();
		for (Holiday holiday : source) {
			LocalDate date = holiday.getDate().withYear(year);
			if (takenNames.contains(lower(holiday.getName())) || !takenDates.add(date)) {
				continue;
			}
			copies.add(new Holiday(location, date, holiday.getName(), holiday.getType(),
					holiday.getType() != HolidayType.NATIONAL));
		}
		if (!copies.isEmpty()) {
			try {
				holidays.saveAllAndFlush(copies);
			}
			catch (DataIntegrityViolationException race) {
				throw new BusinessRuleException(null, "Someone changed these holidays a moment ago. Check the list and try again.");
			}
			audit.record(AuditAction.HOLIDAYS_COPIED, OrgKind.LOCATIONS.label(), locationId,
					"Copied %d %s from %d to %d at %s".formatted(copies.size(),
							(copies.size() == 1) ? "holiday" : "holidays", year - 1, year, location.getName()));
		}
		return new Copied(copies.size(), source.size() - copies.size());
	}

	/**
	 * Sets the location's weekly offs; every week of the month must keep a working day.
	 * @return false when they were already these
	 */
	@PreAuthorize(HR_OR_ADMIN)
	@Transactional
	public boolean setWorkWeek(long locationId, WeeklyOffs offs) {
		WorkLocation location = location(locationId);
		if (!offs.hasWorkingDayEveryWeek()) {
			throw new BusinessRuleException("off", "Leave at least one working day in every week of the month.");
		}
		WorkWeek week = workWeeks.findByLocationId(locationId).orElseGet(() -> new WorkWeek(location));
		WeeklyOffs before = (week.getId() == null) ? WeeklyOffs.DEFAULT : week.offs();
		if (before.equals(offs)) {
			return false;
		}
		week.setOffs(offs);
		try {
			workWeeks.saveAndFlush(week);
		}
		catch (DataIntegrityViolationException race) {
			throw new BusinessRuleException(null, "Someone changed this work week a moment ago. Check it and try again.");
		}
		audit.record(AuditAction.WORK_WEEK_CHANGED, OrgKind.LOCATIONS.label(), locationId,
				"Changed the work week at " + location.getName(),
				AuditChanges.create().field("Weekly offs", before.describe(), offs.describe()));
		return true;
	}

	private void requireValid(long locationId, Long id, Details details) {
		List<Problem> problems = problems(locationId, id, details);
		if (!problems.isEmpty()) {
			throw new BusinessRuleException(problems.getFirst().field(), problems.getFirst().message());
		}
	}

	/** Inserts or updates at once, so a holiday saved on the same date a moment ago fails here, not at commit. */
	private void save(Holiday holiday) {
		try {
			holidays.saveAndFlush(holiday);
		}
		catch (DataIntegrityViolationException race) {
			throw new BusinessRuleException("date", "Another holiday was saved on this date a moment ago.");
		}
	}

	private WeeklyOffs weeklyOffs(WorkLocation location) {
		return workWeeks.findByLocationId(location.getId()).map(WorkWeek::offs).orElse(WeeklyOffs.DEFAULT);
	}

	private WorkLocation location(long id) {
		return locations.findById(id).orElseThrow(() -> new NotFoundException("No work location with id " + id));
	}

	private Holiday find(long id) {
		return holidays.findWithLocationById(id).orElseThrow(() -> new NotFoundException("No holiday with id " + id));
	}

	/** The audited fields, labelled as on the form. */
	private Map<String, Object> values(Holiday holiday) {
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("Location", holiday.getLocation().getName());
		values.put("Date", fmt.date(holiday.getDate()));
		values.put("Name", holiday.getName());
		values.put("Type", holiday.getType().label());
		return values;
	}

	private static LocationRef ref(WorkLocation location) {
		return new LocationRef(location.getId(), location.getName(), location.isActive());
	}

	private static String lower(String name) {
		return name.toLowerCase(Locale.ROOT);
	}

}
