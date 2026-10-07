package com.kosh.user;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.kosh.common.BaseEntity;

/**
 * A sign-in account. The e-mail is the login name, kept trimmed and lower-case so lookups and the unique key ignore
 * case. A {@code null} password hash means the user was invited and has not set a password yet.
 */
@Entity
@Table(name = "users")
public class User extends BaseEntity {

	/** Wrong passwords in a row that lock sign-in. */
	public static final int MAX_FAILED_LOGINS = 5;

	/** How long such a lock lasts. */
	public static final Duration LOCK_DURATION = Duration.ofMinutes(15);

	@Column(nullable = false, length = 254)
	private String email;

	@Column(length = 100)
	private String passwordHash;

	@Column(nullable = false, length = 120)
	private String displayName;

	@Column(nullable = false)
	private boolean enabled = true;

	// VARCHAR, not the native ENUM type Hibernate would expect on MySQL, so the same column validates on H2 and MySQL.
	@ElementCollection
	@CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
	@Column(name = "role", nullable = false, length = 20)
	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	private Set<Role> roles = new HashSet<>();

	@Column(nullable = false)
	private int failedLoginCount;

	private LocalDateTime lockedUntil;

	private LocalDateTime lastLoginAt;

	protected User() {
	}

	public User(String email, String displayName, Collection<Role> roles) {
		setEmail(email);
		setDisplayName(displayName);
		setRoles(roles);
	}

	/** Trimmed and lower-cased, or {@code null} for {@code null}. Use it on every e-mail before comparing. */
	public static String normalizeEmail(String email) {
		return email == null ? null : email.strip().toLowerCase(Locale.ROOT);
	}

	public String getEmail() {
		return email;
	}

	public void setEmail(String email) {
		String normalized = normalizeEmail(email);
		if (normalized == null || normalized.isEmpty()) {
			throw new IllegalArgumentException("A user needs an e-mail address");
		}
		this.email = normalized;
	}

	public String getDisplayName() {
		return displayName;
	}

	public void setDisplayName(String displayName) {
		if (displayName == null || displayName.isBlank()) {
			throw new IllegalArgumentException("A user needs a display name");
		}
		this.displayName = displayName.strip();
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	/** Stores an already encoded hash; this class never sees a plain password. */
	public void setPasswordHash(String passwordHash) {
		this.passwordHash = passwordHash;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public Set<Role> getRoles() {
		return Collections.unmodifiableSet(roles);
	}

	public boolean hasRole(Role role) {
		return roles.contains(role);
	}

	/**
	 * Replaces every role; at least one is required. Updates the managed collection in place so Hibernate writes only
	 * the difference.
	 */
	public void setRoles(Collection<Role> newRoles) {
		Set<Role> wanted = Set.copyOf(Objects.requireNonNull(newRoles, "roles"));
		if (wanted.isEmpty()) {
			throw new IllegalArgumentException("A user needs at least one role");
		}
		roles.retainAll(wanted);
		roles.addAll(wanted);
	}

	public int getFailedLoginCount() {
		return failedLoginCount;
	}

	public LocalDateTime getLockedUntil() {
		return lockedUntil;
	}

	public LocalDateTime getLastLoginAt() {
		return lastLoginAt;
	}

	/**
	 * Counts a wrong password. The {@value #MAX_FAILED_LOGINS}th in a row locks sign-in for {@link #LOCK_DURATION} and
	 * starts the count again, so the owner gets a full set of tries once the lock ends.
	 * @return true when this failure locked the account
	 */
	public boolean recordLoginFailure(LocalDateTime now) {
		if (++failedLoginCount < MAX_FAILED_LOGINS) {
			return false;
		}
		failedLoginCount = 0;
		lockedUntil = now.plus(LOCK_DURATION);
		return true;
	}

	/** Forgets wrong passwords and lifts any lock: a new password or a successful sign-in proves the owner. */
	public void clearLoginFailures() {
		this.failedLoginCount = 0;
		this.lockedUntil = null;
	}

	/** Blocks sign-in until the given time. */
	public void lockUntil(LocalDateTime until) {
		this.lockedUntil = until;
	}

	/** True while a lock is in force; a lock ends at {@code lockedUntil}. */
	public boolean isLockedAt(LocalDateTime now) {
		return lockedUntil != null && now.isBefore(lockedUntil);
	}

	/** A successful sign-in clears failures and any lock, and remembers when it happened. */
	public void recordLoginSuccess(LocalDateTime at) {
		clearLoginFailures();
		this.lastLoginAt = at;
	}

}
