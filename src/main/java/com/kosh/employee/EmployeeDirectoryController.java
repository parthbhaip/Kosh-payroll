package com.kosh.employee;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.kosh.common.NotFoundException;
import com.kosh.org.OrgKind;
import com.kosh.org.OrgService;

/**
 * {@code /hr/employees}: the employee directory and each employee's profile, one tab per URL (PLAN T16). HR and ADMIN
 * only (URL rule and service). The top bar's employee search sends its {@code q} here.
 */
@Controller
@RequestMapping("/hr/employees")
class EmployeeDirectoryController {

	static final int PAGE_SIZE = 25;

	/** Sortable columns; anything else in {@code ?sort=} is ignored. */
	private static final Set<String> SORTABLE = Set.of("firstName", "code", "department.name", "joiningDate");

	/**
	 * Typed names sort case-insensitively, as every list does (H2 compares case-sensitively). Codes are stored upper
	 * case and dates have no case: sorting them on lower() would only stop their indexes serving the sort.
	 */
	private static final Set<String> TYPED = Set.of("firstName", "department.name");

	private final EmployeeDirectoryService directory;

	private final OrgService org;

	EmployeeDirectoryController(EmployeeDirectoryService directory, OrgService org) {
		this.directory = directory;
		this.org = org;
	}

	@GetMapping
	String list(@RequestParam(required = false) String q, @RequestParam(required = false) String department,
			@RequestParam(required = false) String location, @RequestParam(required = false) String status,
			@RequestParam(required = false) String type, @PageableDefault Pageable pageable, Model model) {
		EmployeeFilter filter = new EmployeeFilter(q, id(department), id(location),
				option(EmployeeStatus.class, status), option(EmploymentType.class, type));
		Sort.Order requested = pageable.getSort()
			.stream()
			.filter(candidate -> SORTABLE.contains(candidate.getProperty()))
			.findFirst()
			.orElse(null);
		Sort.Order order = (requested != null) ? requested : Sort.Order.asc("firstName");
		if (TYPED.contains(order.getProperty())) {
			order = order.ignoreCase();
		}
		Page<Employee> page = directory.list(filter, PageRequest.of(pageable.getPageNumber(), PAGE_SIZE, order(order)));
		if (page.isEmpty() && page.getTotalPages() > 0) {
			// A page past the end (an old link after the list shrank): show the last one.
			page = directory.list(filter, PageRequest.of(page.getTotalPages() - 1, PAGE_SIZE, order(order)));
		}
		model.addAttribute("page", page);
		Map<EmployeeStatus, Long> counts = directory.statusCounts(filter);
		model.addAttribute("sort", Sort.by(order));
		// The filter form keeps a sort the user chose by its column header.
		model.addAttribute("sortParam", (requested != null)
				? order.getProperty() + "," + order.getDirection().name().toLowerCase(Locale.ROOT) : null);
		model.addAttribute("filter", filter);
		model.addAttribute("counts", counts);
		model.addAttribute("total", counts.values().stream().mapToLong(Long::longValue).sum());
		model.addAttribute("departments", org.list(OrgKind.DEPARTMENTS, Sort.by(Sort.Order.asc("name").ignoreCase())));
		model.addAttribute("locations", org.list(OrgKind.LOCATIONS, Sort.by(Sort.Order.asc("name").ignoreCase())));
		model.addAttribute("statuses", EmployeeStatus.values());
		model.addAttribute("types", EmploymentType.values());
		return "hr/employees/list";
	}

	@GetMapping("/{id}")
	String overview(@PathVariable long id, Authentication viewer, Model model) {
		return profile(id, ProfileTab.OVERVIEW, viewer, model);
	}

	@GetMapping("/{id}/{slug}")
	String tab(@PathVariable long id, @PathVariable String slug, Authentication viewer, Model model) {
		ProfileTab tab = ProfileTab.fromSlug(slug).orElseThrow(() -> new NotFoundException("No profile tab " + slug));
		return profile(id, tab, viewer, model);
	}

	private String profile(long id, ProfileTab tab, Authentication viewer, Model model) {
		model.addAttribute("profile", directory.profile(id, tab, viewer));
		return "hr/employees/profile";
	}

	/** The sort the query runs: names fall back to the last name, and the id keeps paging stable on ties. */
	private static Sort order(Sort.Order order) {
		Sort sort = Sort.by(order);
		if (order.getProperty().equals("firstName")) {
			sort = sort.and(Sort.by(order.withProperty("lastName")));
		}
		return sort.and(Sort.by("id"));
	}

	/** An id from a filter, or null for "all" and for anything that is not a number. */
	private static Long id(String value) {
		try {
			return (value == null || value.isBlank()) ? null : Long.valueOf(value.strip());
		}
		catch (NumberFormatException notANumber) {
			return null;
		}
	}

	/** A choice from a filter by its name, or null for "all" and for anything that is not a choice. */
	private static <E extends Enum<E>> E option(Class<E> type, String value) {
		for (E candidate : type.getEnumConstants()) {
			if (candidate.name().equals(value)) {
				return candidate;
			}
		}
		return null;
	}

}
