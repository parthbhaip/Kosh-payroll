package com.kosh.user;

import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Users by e-mail. E-mails are stored normalised ({@link User#normalizeEmail}), so the case-insensitive lookups
 * normalise their input and hit the unique key directly instead of comparing {@code LOWER(email)} row by row.
 */
public interface UserRepository extends JpaRepository<User, Long> {

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

}
