package com.kosh.employee;

import java.time.Clock;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kosh.common.Fmt;
import com.kosh.common.ForbiddenException;
import com.kosh.common.NotFoundException;
import com.kosh.user.Role;

/**
 * The employee directory and profiles for HR and ADMIN (PLAN T16): search and filters, status counts, and one profile
 * tab at a time. The Salary tab is refused to anyone outside the salary roles, here as well as by hiding the tab; the
 * PAN is shown in full only to HR (CLAUDE.md > Sensitive data), the bank account always masked.
 */
@Service
@PreAuthorize("hasAnyRole('HR', 'ADMIN')")
public class EmployeeDirectoryService {

	/**
	 * One tab of a profile. {@code tabs}: the tabs this viewer sees; {@code fullPan}: show the PAN unmasked;
	 * {@code age} and {@code service}: today, from the clock; {@code reports} (Job tab only): the current direct
	 * reports; {@code history} (History tab only): the record's dated events, oldest first.
	 */
	public record Profile(Employee employee, ProfileTab tab, List<ProfileTab> tabs, boolean fullPan, int age,
			String service, List<Employee> reports, List<Event> history) {

	}

	/** A line in the History timeline, in the shape the timeline fragment reads. */
	public record Event(String title, String meta, String body, String tone) {

	}

	private final EmployeeRepository employees;

	private final Fmt fmt;

	private final Clock clock;

	public EmployeeDirectoryService(EmployeeRepository employees, Fmt fmt, Clock clock) {
		this.employees = employees;
		this.fmt = fmt;
		this.clock = clock;
	}

	/** A page of the employees the filter matches, with department, designation, location and manager loaded. */
	@Transactional(readOnly = true)
	public Page<Employee> list(EmployeeFilter filter, Pageable pageable) {
		return employees.findAll(filter.spec(), pageable);
	}

	/** How many employees the filter matches in each status, whatever status it asks for (for the status chips). */
	@Transactional(readOnly = true)
	public Map<EmployeeStatus, Long> statusCounts(EmployeeFilter filter) {
		Map<EmployeeStatus, Long> counts = new EnumMap<>(EmployeeStatus.class);
		for (EmployeeStatus status : EmployeeStatus.values()) {
			counts.put(status, employees.count(filter.withStatus(status).spec()));
		}
		return counts;
	}

	/**
	 * One tab of an employee's profile.
	 * @throws ForbiddenException when the viewer may not see that tab (Salary without a salary role)
	 * @throws NotFoundException when there is no such employee
	 */
	@Transactional(readOnly = true)
	public Profile profile(long id, ProfileTab tab, Authentication viewer) {
		Set<Role> roles = Role.fromAuthorities(viewer.getAuthorities());
		if (!tab.visibleTo(roles)) {
			throw new ForbiddenException("Salary details need a salary role");
		}
		Employee employee = employees.findWithDetailsById(id)
			.orElseThrow(() -> new NotFoundException("No employee " + id));
		LocalDate today = LocalDate.now(clock);
		List<Employee> reports = (tab == ProfileTab.JOB) ? employees.findTeam(id) : List.of();
		List<Event> history = (tab == ProfileTab.HISTORY) ? history(employee, today) : List.of();
		return new Profile(employee, tab, ProfileTab.visibleTabs(roles), roles.contains(Role.HR),
				Period.between(employee.getDateOfBirth(), today).getYears(), service(employee, today), reports,
				history);
	}

	/** {@code 2 years 6 months}, {@code 3 months}, {@code Less than a month}; {@code Joins on 1 Nov 2026} ahead of time. */
	private String service(Employee employee, LocalDate today) {
		LocalDate joined = employee.getJoiningDate();
		if (joined.isAfter(today)) {
			return "Joins on " + fmt.date(joined);
		}
		Period period = Period.between(joined, today);
		List<String> parts = new ArrayList<>();
		if (period.getYears() > 0) {
			parts.add(plural(period.getYears(), "year"));
		}
		if (period.getMonths() > 0) {
			parts.add(plural(period.getMonths(), "month"));
		}
		return parts.isEmpty() ? "Less than a month" : String.join(" ", parts);
	}

	private List<Event> history(Employee employee, LocalDate today) {
		record Dated(LocalDate date, Event event) {

		}
		List<Dated> events = new ArrayList<>();
		LocalDate joined = employee.getJoiningDate();
		// No type or location here: the record holds only today's, which need not be what the person joined on.
		events.add(new Dated(joined,
				new Event(joined.isAfter(today) ? "Joins" : "Joined", fmt.date(joined), null, "success")));
		LocalDate confirmed = employee.getConfirmationDate();
		if (confirmed != null) {
			boolean due = confirmed.isAfter(today);
			events.add(new Dated(confirmed,
					new Event(due ? "Confirmation due" : "Confirmed", fmt.date(confirmed), null, due ? "info" : "success")));
		}
		LocalDate contractEnd = employee.getContractEndDate();
		if (contractEnd != null) {
			boolean ahead = !contractEnd.isBefore(today);
			events.add(new Dated(contractEnd, new Event(ahead ? "Contract ends" : "Contract ended", fmt.date(contractEnd),
					null, ahead ? "warning" : null)));
		}
		LocalDate added = employee.getCreatedAt().toLocalDate();
		events.add(new Dated(added, new Event("Record added to Kosh", fmt.date(added), null, null)));
		return events.stream().sorted(Comparator.comparing(Dated::date)).map(Dated::event).toList();
	}

	private static String plural(int count, String unit) {
		return count + " " + unit + (count == 1 ? "" : "s");
	}

}
