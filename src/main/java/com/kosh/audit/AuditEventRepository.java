package com.kosh.audit;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.criteria.Predicate;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import com.kosh.common.Like;

/** Audit events. Append-only: the application saves and reads events, never updates or deletes them. */
public interface AuditEventRepository extends JpaRepository<AuditEvent, Long>, JpaSpecificationExecutor<AuditEvent> {

	/** Ids grow with time, so the newest event comes first; the primary key serves the order. */
	Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "id");

	/** The kinds of record that have events, for the filter. */
	@Query("select distinct e.entityType from AuditEvent e where e.entityType is not null order by e.entityType")
	List<String> findEntityTypes();

	/**
	 * Events matching the filter and, when {@code beforeId} is set, older than that event (for reading in batches).
	 * ponytail: the actor filter is a "contains" match, which no index serves; the date range keeps scans short. Add
	 * an exact actor filter if the table gets large.
	 */
	static Specification<AuditEvent> matching(AuditFilter filter, Long beforeId) {
		return (root, query, cb) -> {
			List<Predicate> where = new ArrayList<>();
			if (filter.actor() != null) {
				String like = Like.containing(filter.actor());
				where.add(cb.or(cb.like(cb.lower(root.get("actorName")), like, Like.ESCAPE),
						cb.like(root.get("actorEmail"), like, Like.ESCAPE)));
			}
			if (filter.action() != null) {
				where.add(cb.equal(root.get("action"), filter.action()));
			}
			if (filter.entityType() != null) {
				where.add(cb.equal(root.get("entityType"), filter.entityType()));
			}
			if (filter.entityId() != null) {
				where.add(cb.equal(root.get("entityId"), filter.entityId()));
			}
			if (filter.from() != null) {
				where.add(cb.greaterThanOrEqualTo(root.<LocalDateTime>get("occurredAt"), filter.from().atStartOfDay()));
			}
			if (filter.to() != null) {
				where.add(cb.lessThan(root.<LocalDateTime>get("occurredAt"), filter.to().plusDays(1).atStartOfDay()));
			}
			if (beforeId != null) {
				where.add(cb.lessThan(root.<Long>get("id"), beforeId));
			}
			return cb.and(where.toArray(Predicate[]::new));
		};
	}

}
