package com.kosh.user;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import jakarta.persistence.criteria.Predicate;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import com.kosh.common.Like;

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

	/**
	 * Internal: exact match with a row lock until the transaction ends, so concurrent sign-ins count failures one at a
	 * time instead of failing on the version check. Call {@link #lockByEmailIgnoreCase}.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select u from User u where u.email = :email")
	Optional<User> findForUpdateByEmail(String email);

	/** The user with this e-mail in any case, row-locked for the rest of the transaction. Needs a transaction. */
	default Optional<User> lockByEmailIgnoreCase(String email) {
		String normalized = User.normalizeEmail(email);
		return normalized == null || normalized.isEmpty() ? Optional.empty() : findForUpdateByEmail(normalized);
	}

	/** Whether any user already has this e-mail in any case. */
	default boolean existsByEmailIgnoreCase(String email) {
		String normalized = User.normalizeEmail(email);
		return normalized != null && !normalized.isEmpty() && existsExactlyByEmail(normalized);
	}

	/**
	 * One page of users whose name or e-mail contains {@code text} (any case; blank matches everyone) and who hold
	 * {@code role} (null for any). Roles are not fetched here, because a collection fetch cannot be paged in the
	 * database; load them for the page with {@link #findWithRolesByIdIn}.
	 */
	default Page<User> search(String text, Role role, Pageable pageable) {
		Specification<User> matching = (root, query, cb) -> {
			List<Predicate> where = new ArrayList<>();
			if (text != null && !text.isBlank()) {
				String like = Like.containing(text);
				where.add(cb.or(cb.like(cb.lower(root.get("displayName")), like, Like.ESCAPE),
						cb.like(root.get("email"), like, Like.ESCAPE)));
			}
			if (role != null) {
				where.add(cb.isMember(role, root.get("roles")));
			}
			return cb.and(where.toArray(Predicate[]::new));
		};
		return findAll(matching, pageable);
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

	/** Enabled users with the Employee role, by name: the people who can head a department. */
	@Query("""
			select u from User u join u.roles r
			where r = com.kosh.user.Role.EMPLOYEE and u.enabled = true order by u.displayName, u.id""")
	List<User> findEnabledEmployees();

	/** Admins who can sign in now (see {@link #countOtherActiveAdmins}). */
	@Query("""
			select count(u) from User u join u.roles r
			where r = com.kosh.user.Role.ADMIN and u.enabled = true and u.passwordHash is not null""")
	long countActiveAdmins();

}
