package com.kosh.user;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import jakarta.persistence.criteria.Predicate;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

/**
 * Users by e-mail. E-mails are stored normalised ({@link User#normalizeEmail}), so the case-insensitive lookups
 * normalise their input and hit the unique key directly instead of comparing {@code LOWER(email)} row by row.
 */
public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {

	/**
	 * Internal: exact match on an already normalised e-mail. H2 compares case-sensitively but MySQL's ci collation
	 * does not, so raw input behaves differently per database. Call {@link #findByEmailIgnoreCase} instead.
	 */
	@EntityGraph(attributePaths = "roles")
	Optional<User> findWithRolesByEmail(String email);

	/** Internal: exact match, same caveat as {@link #findWithRolesByEmail}. Call {@link #existsByEmailIgnoreCase}. */
	boolean existsExactlyByEmail(String email);

	/** The user with this e-mail in any case and with surrounding spaces, roles loaded. */
	default Optional<User> findByEmailIgnoreCase(String email) {
		String normalized = User.normalizeEmail(email);
		return normalized == null || normalized.isEmpty() ? Optional.empty() : findWithRolesByEmail(normalized);
	}

	/** Whether any user already has this e-mail in any case. */
	default boolean existsByEmailIgnoreCase(String email) {
		String normalized = User.normalizeEmail(email);
		return normalized != null && !normalized.isEmpty() && existsExactlyByEmail(normalized);
	}

	/** Escape character for LIKE patterns: not a backslash, which MySQL string literals treat as an escape. */
	char LIKE_ESCAPE = '!';

	/**
	 * One page of users whose name or e-mail contains {@code text} (any case; blank matches everyone) and who hold
	 * {@code role} (null for any). Roles are not fetched here, because a collection fetch cannot be paged in the
	 * database; load them for the page with {@link #findWithRolesByIdIn}.
	 */
	default Page<User> search(String text, Role role, Pageable pageable) {
		Specification<User> matching = (root, query, cb) -> {
			List<Predicate> where = new ArrayList<>();
			if (text != null && !text.isBlank()) {
				String like = "%" + escapeLike(text.strip().toLowerCase(Locale.ROOT)) + "%";
				where.add(cb.or(cb.like(cb.lower(root.get("displayName")), like, LIKE_ESCAPE),
						cb.like(root.get("email"), like, LIKE_ESCAPE)));
			}
			if (role != null) {
				where.add(cb.isMember(role, root.get("roles")));
			}
			return cb.and(where.toArray(Predicate[]::new));
		};
		return findAll(matching, pageable);
	}

	/** Typed text as a literal LIKE fragment: {@code %}, {@code _} and the escape character lose their meaning. */
	static String escapeLike(String text) {
		return text.replace("!", "!!").replace("%", "!%").replace("_", "!_");
	}

	/** The users with these ids, roles loaded in the same query. */
	@EntityGraph(attributePaths = "roles")
	List<User> findWithRolesByIdIn(Collection<Long> ids);

	/**
	 * Admins other than {@code userId} who can sign in now: enabled, with a password, holding ADMIN. An invited admin
	 * does not count, because the invite link can expire.
	 */
	@Query("""
			select count(u) from User u join u.roles r
			where r = com.kosh.user.Role.ADMIN and u.enabled = true and u.passwordHash is not null and u.id <> :userId""")
	long countOtherActiveAdmins(Long userId);

}
