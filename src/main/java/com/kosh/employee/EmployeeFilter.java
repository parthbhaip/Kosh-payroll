package com.kosh.employee;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.criteria.Predicate;

import org.springframework.data.jpa.domain.Specification;

import com.kosh.common.Like;

/**
 * What the employee directory is narrowed to (PLAN T16); every part is optional (null or blank for any). {@code text}
 * matches names and codes word by word, so "ravi patil" finds Ravi Shankar Patil and "0012" finds KSH-0012.
 */
public record EmployeeFilter(String text, Long departmentId, Long locationId, EmployeeStatus status,
		EmploymentType type) {

	/** The longest search kept, as the search box allows; each word costs four LIKEs in every query. */
	public static final int MAX_TEXT = 100;

	public EmployeeFilter {
		text = (text == null) ? "" : text.strip();
		if (text.length() > MAX_TEXT) {
			text = text.substring(0, MAX_TEXT).strip();
		}
	}

	/** Whether anything narrows the list (status included). */
	public boolean narrowed() {
		return !text.isEmpty() || departmentId != null || locationId != null || status != null || type != null;
	}

	/** The same filter with another status (null for any), for the status chips. */
	public EmployeeFilter withStatus(EmployeeStatus other) {
		return new EmployeeFilter(text, departmentId, locationId, other, type);
	}

	public Specification<Employee> spec() {
		return (root, query, cb) -> {
			List<Predicate> where = new ArrayList<>();
			for (String word : text.split("\\s+")) {
				if (!word.isEmpty()) {
					String like = Like.containing(word);
					where.add(cb.or(cb.like(cb.lower(root.get("firstName")), like, Like.ESCAPE),
							cb.like(cb.lower(root.get("middleName")), like, Like.ESCAPE),
							cb.like(cb.lower(root.get("lastName")), like, Like.ESCAPE),
							cb.like(cb.lower(root.get("code")), like, Like.ESCAPE)));
				}
			}
			if (departmentId != null) {
				where.add(cb.equal(root.get("department").get("id"), departmentId));
			}
			if (locationId != null) {
				where.add(cb.equal(root.get("location").get("id"), locationId));
			}
			if (status != null) {
				where.add(cb.equal(root.get("status"), status));
			}
			if (type != null) {
				where.add(cb.equal(root.get("employmentType"), type));
			}
			return cb.and(where.toArray(Predicate[]::new));
		};
	}

}
