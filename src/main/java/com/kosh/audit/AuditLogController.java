package com.kosh.audit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * {@code /admin/audit}: the audit log with filters (actor, action, record, date range), 50 events a page, newest
 * first, and the same selection as a CSV download. ADMIN only (URL rule and service).
 */
@Controller
@RequestMapping("/admin/audit")
class AuditLogController {

	static final int PAGE_SIZE = 50;

	static final String RANGE_ERROR = "The end date is before the start date.";

	private final AuditService audit;

	private final Clock clock;

	AuditLogController(AuditService audit, Clock clock) {
		this.audit = audit;
		this.clock = clock;
	}

	@GetMapping
	String list(@ModelAttribute("filter") FilterForm form, Pageable pageable, HttpServletRequest request, Model model) {
		AuditFilter filter = form.toFilter();
		boolean rangeError = form.rangeError();
		model.addAttribute("page", rangeError ? null
				: audit.list(filter, PageRequest.of(Math.max(pageable.getPageNumber(), 0), PAGE_SIZE)));
		model.addAttribute("rangeError", rangeError ? RANGE_ERROR : null);
		model.addAttribute("filtered", !filter.isEmpty());
		model.addAttribute("actions", AuditAction.values());
		model.addAttribute("entityTypes", audit.entityTypes());
		model.addAttribute("exportHref", UriComponentsBuilder.fromPath(request.getRequestURI() + "/export")
			.query(request.getQueryString())
			.replaceQueryParam("page")
			.build()
			.toUriString());
		return "admin/audit/list";
	}

	/**
	 * The filtered events as {@code kosh-audit-log-<date>.csv}. A bad date range goes back to the page to show why. A
	 * POST with the CSRF token, because it records an event: another site cannot make an admin's browser add one. The
	 * filter stays in the query string, so the page posts to the same URL it used to link to.
	 */
	@PostMapping("/export")
	String export(@ModelAttribute("filter") FilterForm form, HttpServletRequest request, HttpServletResponse response)
			throws IOException {
		if (form.rangeError()) {
			return "redirect:"
					+ UriComponentsBuilder.fromPath("/admin/audit").query(request.getQueryString()).build().toUriString();
		}
		response.setContentType("text/csv");
		response.setCharacterEncoding(StandardCharsets.UTF_8);
		response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
			.filename("kosh-audit-log-" + LocalDate.now(clock) + ".csv")
			.build()
			.toString());
		audit.export(form.toFilter(), response.getWriter());
		return null;
	}

	/**
	 * The filter as typed in the query string. Anything unreadable (an unknown action, a date that is not
	 * {@code yyyy-mm-dd}, a non-numeric record id) is ignored, as the date inputs and selects never send one.
	 */
	public static class FilterForm {

		private String actor;

		private String action;

		private String entity;

		private String entityId;

		private String from;

		private String to;

		AuditFilter toFilter() {
			return new AuditFilter(blankToNull(actor), parseAction(action), blankToNull(entity), parseId(entityId),
					parseDate(from), parseDate(to));
		}

		/** Both dates given and the end before the start. */
		boolean rangeError() {
			LocalDate start = parseDate(from);
			LocalDate end = parseDate(to);
			return start != null && end != null && end.isBefore(start);
		}

		private static String blankToNull(String text) {
			return (text == null || text.isBlank()) ? null : text.strip();
		}

		private static AuditAction parseAction(String name) {
			for (AuditAction candidate : AuditAction.values()) {
				if (candidate.name().equals(name)) {
					return candidate;
				}
			}
			return null;
		}

		private static Long parseId(String text) {
			try {
				return (text == null || text.isBlank()) ? null : Long.valueOf(text.strip());
			}
			catch (NumberFormatException notAnId) {
				return null;
			}
		}

		private static LocalDate parseDate(String text) {
			try {
				return (text == null || text.isBlank()) ? null : LocalDate.parse(text.strip());
			}
			catch (DateTimeParseException notADate) {
				return null;
			}
		}

		public String getActor() {
			return actor;
		}

		public void setActor(String actor) {
			this.actor = actor;
		}

		public String getAction() {
			return action;
		}

		public void setAction(String action) {
			this.action = action;
		}

		public String getEntity() {
			return entity;
		}

		public void setEntity(String entity) {
			this.entity = entity;
		}

		public String getEntityId() {
			return entityId;
		}

		public void setEntityId(String entityId) {
			this.entityId = entityId;
		}

		public String getFrom() {
			return from;
		}

		public void setFrom(String from) {
			this.from = from;
		}

		public String getTo() {
			return to;
		}

		public void setTo(String to) {
			this.to = to;
		}

	}

}
