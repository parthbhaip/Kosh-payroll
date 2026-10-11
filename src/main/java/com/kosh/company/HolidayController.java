package com.kosh.company;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.IntStream;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;

import com.kosh.common.BusinessRuleException;
import com.kosh.common.NotFoundException;
import com.kosh.company.HolidayService.Copied;
import com.kosh.company.HolidayService.Item;
import com.kosh.company.HolidayService.LocationRef;
import com.kosh.company.HolidayService.Problem;
import com.kosh.company.HolidayService.Saved;
import com.kosh.company.HolidayService.YearView;
import com.kosh.web.Flash;

/**
 * {@code /hr/holidays}: each work location's holidays by calendar year, its work week, adding, editing and deleting
 * holidays and copying last year's list. HR and ADMIN only (URL rule and service).
 */
@Controller
@RequestMapping("/hr/holidays")
class HolidayController {

	private static final String BASE = "/hr/holidays";

	private final HolidayService service;

	private final Clock clock;

	HolidayController(HolidayService service, Clock clock) {
		this.service = service;
		this.clock = clock;
	}

	/**
	 * One location's year. Without {@code location}, the first active location; an unreadable or out-of-range
	 * {@code year} shows the current year.
	 */
	@GetMapping
	String list(@RequestParam(required = false) String location, @RequestParam(required = false) String year,
			Model model) {
		int thisYear = LocalDate.now(clock).getYear();
		int shown = year(year, thisYear);
		List<LocationRef> locations = service.locations();
		model.addAttribute("year", shown);
		if (locations.isEmpty()) {
			return "hr/holidays/list";
		}
		LocationRef current = current(location, locations);
		YearView view = service.year(current.id(), shown);
		Map<Long, String> locationOptions = new LinkedHashMap<>();
		for (LocationRef option : locations) {
			if (option.active() || option.id() == current.id()) {
				locationOptions.put(option.id(), option.active() ? option.name() : option.name() + " (inactive)");
			}
		}
		Set<Integer> years = new TreeSet<>();
		IntStream.rangeClosed(thisYear - 3, thisYear + 2)
			.filter(option -> option >= HolidayService.FIRST_YEAR && option <= HolidayService.LAST_YEAR)
			.forEach(years::add);
		years.add(shown);
		model.addAttribute("view", view);
		model.addAttribute("locationOptions", locationOptions);
		model.addAttribute("years", years);
		if (view.toCheckCount() > 0) {
			model.addAttribute("checkNotice", Flash
				.warning(view.toCheckCount() == 1 ? "1 copied date needs checking"
						: view.toCheckCount() + " copied dates need checking")
				.withText("Festival and optional dates usually move every year. Open each holiday marked “Check date” and save it with this year’s date."));
		}
		return "hr/holidays/list";
	}

	@GetMapping("/new")
	String newHoliday(@RequestParam long location, @RequestParam(required = false) String year, Model model) {
		LocationRef ref = service.locationRef(location);
		model.addAttribute("form", new HolidayForm());
		return formView(ref, null, year(year, LocalDate.now(clock).getYear()), model);
	}

	@PostMapping
	String create(@RequestParam long location, @RequestParam(required = false) String year,
			@ModelAttribute("form") HolidayForm form, BindingResult errors, Model model, RedirectAttributes redirect) {
		LocationRef ref = service.locationRef(location);
		HolidayService.Details details = form.toDetails();
		check(location, null, details, errors);
		if (!errors.hasErrors()) {
			try {
				service.create(location, details);
				redirect.addFlashAttribute(Flash.ATTRIBUTE, Flash.success(details.name() + " added"));
				return redirectToList(location, details.date().getYear());
			}
			catch (BusinessRuleException rule) {
				rule.addTo(errors);
			}
		}
		return formView(ref, null, year(year, LocalDate.now(clock).getYear()), model);
	}

	@GetMapping("/{id}")
	String edit(@PathVariable long id, Model model) {
		Item item = service.item(id);
		model.addAttribute("form", HolidayForm.of(item.details()));
		return formView(item.location(), item, item.details().date().getYear(), model);
	}

	@PostMapping("/{id}")
	String update(@PathVariable long id, @ModelAttribute("form") HolidayForm form, BindingResult errors, Model model,
			RedirectAttributes redirect) {
		Item item = service.item(id);
		HolidayService.Details details = form.toDetails();
		check(item.location().id(), id, details, errors);
		if (!errors.hasErrors()) {
			try {
				Saved saved = service.update(id, details);
				Flash flash;
				if (saved.changed().isEmpty()) {
					flash = Flash.info("Nothing to save").withText(saved.name() + " is as it was.");
				}
				else if (saved.onlyConfirmed()) {
					flash = Flash.success("Date of " + saved.name() + " confirmed");
				}
				else {
					flash = Flash.success("Saved " + saved.name())
						.withText("Changed: " + String.join(", ", saved.changed()) + ".");
				}
				redirect.addFlashAttribute(Flash.ATTRIBUTE, flash);
				return redirectToList(saved.locationId(), saved.year());
			}
			catch (BusinessRuleException rule) {
				rule.addTo(errors);
			}
		}
		return formView(item.location(), item, item.details().date().getYear(), model);
	}

	@PostMapping("/{id}/delete")
	String delete(@PathVariable long id, RedirectAttributes redirect) {
		Saved deleted = service.delete(id);
		redirect.addFlashAttribute(Flash.ATTRIBUTE, Flash.success(deleted.name() + " deleted"));
		return redirectToList(deleted.locationId(), deleted.year());
	}

	@PostMapping("/copy")
	String copy(@RequestParam long location, @RequestParam int year, RedirectAttributes redirect) {
		try {
			Copied copied = service.copyPreviousYear(location, year);
			Flash flash;
			if (copied.copied() == 0) {
				flash = Flash.info("Nothing to copy")
					.withText("Every %d holiday is already in %d, on its date or by its name.".formatted(year - 1, year));
			}
			else {
				String text = "Festival and optional dates usually move every year: check each one marked “Check date”.";
				if (copied.skipped() > 0) {
					text = "%d already in %d %s skipped. %s".formatted(copied.skipped(), year,
							(copied.skipped() == 1) ? "was" : "were", text);
				}
				flash = Flash.success("Copied %d %s from %d".formatted(copied.copied(),
						(copied.copied() == 1) ? "holiday" : "holidays", year - 1))
					.withText(text);
			}
			redirect.addFlashAttribute(Flash.ATTRIBUTE, flash);
		}
		catch (BusinessRuleException rule) {
			redirect.addFlashAttribute(Flash.ATTRIBUTE, Flash.danger(rule.getMessage()));
		}
		return redirectToList(location, year);
	}

	/** The grid; {@code year} is the list to go back to (read like the list's). */
	@GetMapping("/work-week/{locationId}")
	String workWeek(@PathVariable long locationId, @RequestParam(required = false) String year, Model model) {
		LocationRef ref = service.locationRef(locationId);
		WeeklyOffs offs = service.workWeek(locationId);
		model.addAttribute("form", WorkWeekForm.of(offs));
		return workWeekView(ref, offs, year(year, LocalDate.now(clock).getYear()), model);
	}

	@PostMapping("/work-week/{locationId}")
	String saveWorkWeek(@PathVariable long locationId, @RequestParam(required = false) String year,
			@ModelAttribute("form") WorkWeekForm form, BindingResult errors, Model model, RedirectAttributes redirect) {
		LocationRef ref = service.locationRef(locationId);
		int back = year(year, LocalDate.now(clock).getYear());
		try {
			WeeklyOffs offs = form.toOffs();
			boolean changed = service.setWorkWeek(locationId, offs);
			redirect.addFlashAttribute(Flash.ATTRIBUTE, changed
					? Flash.success("Work week saved").withText(ref.name() + ": " + offs.describe() + ".")
					: Flash.info("Nothing to save").withText("The work week at " + ref.name() + " is as it was."));
			return redirectToList(locationId, back);
		}
		catch (IllegalArgumentException unknown) {
			errors.rejectValue("off", "rule", "Choose the weekly offs from the table.");
		}
		catch (BusinessRuleException rule) {
			rule.addTo(errors);
		}
		return workWeekView(ref, service.workWeek(locationId), back, model);
	}

	/** Every rule broken, next to its field; a field that already failed to bind keeps that one message. */
	private void check(long locationId, Long id, HolidayService.Details details, BindingResult errors) {
		for (Problem problem : service.problems(locationId, id, details)) {
			if (!errors.hasFieldErrors(problem.field())) {
				errors.rejectValue(problem.field(), "rule", problem.message());
			}
		}
	}

	private String formView(LocationRef location, Item item, int year, Model model) {
		model.addAttribute("location", location);
		model.addAttribute("item", item);
		model.addAttribute("year", year);
		model.addAttribute("types", HolidayType.values());
		if (item != null && item.dateToCheck()) {
			model.addAttribute("checkNotice", Flash.warning("Copied from last year: check the date")
				.withText("Festival dates usually move every year. Saving the holiday confirms its date."));
		}
		return "hr/holidays/edit";
	}

	private String workWeekView(LocationRef location, WeeklyOffs current, int year, Model model) {
		model.addAttribute("location", location);
		model.addAttribute("current", current);
		model.addAttribute("year", year);
		model.addAttribute("days", DayOfWeek.values());
		Map<Integer, String> weeks = new LinkedHashMap<>();
		IntStream.rangeClosed(1, WeeklyOffs.WEEKS).forEach(week -> weeks.put(week, PaySettings.ordinal(week)));
		model.addAttribute("weeks", weeks);
		return "hr/holidays/work-week";
	}

	/** The requested location, or the first active one (the first of all when none is active). */
	private static LocationRef current(String requested, List<LocationRef> locations) {
		if (requested != null && !requested.isBlank()) {
			long id;
			try {
				id = Long.parseLong(requested.strip());
			}
			catch (NumberFormatException unreadable) {
				throw new NotFoundException("No work location " + requested);
			}
			return locations.stream()
				.filter(location -> location.id() == id)
				.findFirst()
				.orElseThrow(() -> new NotFoundException("No work location with id " + id));
		}
		return locations.stream().filter(LocationRef::active).findFirst().orElse(locations.getFirst());
	}

	private static int year(String requested, int fallback) {
		if (requested == null) {
			return fallback;
		}
		try {
			int year = Integer.parseInt(requested.strip());
			return (year >= HolidayService.FIRST_YEAR && year <= HolidayService.LAST_YEAR) ? year : fallback;
		}
		catch (NumberFormatException unreadable) {
			return fallback;
		}
	}

	private static String redirectToList(long locationId, int year) {
		return "redirect:" + UriComponentsBuilder.fromPath(BASE)
			.queryParam("location", locationId)
			.queryParam("year", year)
			.build()
			.toUriString();
	}

	/** The add and edit form. */
	public static class HolidayForm {

		@DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
		private LocalDate date;

		private String name;

		private HolidayType type;

		static HolidayForm of(HolidayService.Details details) {
			HolidayForm form = new HolidayForm();
			form.date = details.date();
			form.name = details.name();
			form.type = details.type();
			return form;
		}

		HolidayService.Details toDetails() {
			return new HolidayService.Details(date, name, type);
		}

		public LocalDate getDate() {
			return date;
		}

		public void setDate(LocalDate date) {
			this.date = date;
		}

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public HolidayType getType() {
			return type;
		}

		public void setType(HolidayType type) {
			this.type = type;
		}

	}

	/** The work week grid: one ticked box per day and week of the month that is off, as {@code SATURDAY:2}. */
	public static class WorkWeekForm {

		private Set<String> off = new LinkedHashSet<>();

		static WorkWeekForm of(WeeklyOffs offs) {
			WorkWeekForm form = new WorkWeekForm();
			for (DayOfWeek day : DayOfWeek.values()) {
				for (int week = 1; week <= WeeklyOffs.WEEKS; week++) {
					if (offs.isOff(day, week)) {
						form.off.add(day.name() + ":" + week);
					}
				}
			}
			return form;
		}

		/** @throws IllegalArgumentException for a box the table does not have */
		WeeklyOffs toOffs() {
			return WeeklyOffs.parse(String.join(" ", off));
		}

		public boolean isOff(DayOfWeek day, int week) {
			return off.contains(day.name() + ":" + week);
		}

		public Set<String> getOff() {
			return off;
		}

		public void setOff(Set<String> off) {
			this.off = (off == null) ? new LinkedHashSet<>() : off;
		}

	}

}
