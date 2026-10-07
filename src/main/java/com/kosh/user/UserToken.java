package com.kosh.user;

import java.time.Duration;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.kosh.common.BaseEntity;

/**
 * A single-use link e-mailed to a user. Only the keyed hash of the token is stored; the token itself exists only in the
 * e-mail. See {@link PasswordLinkService}.
 */
@Entity
@Table(name = "user_tokens")
public class UserToken extends BaseEntity {

	public enum Purpose {

		/** Set a first password after an admin invited the user. */
		INVITE(Duration.ofHours(48));

		private final Duration validity;

		Purpose(Duration validity) {
			this.validity = validity;
		}

		/** How long a new link of this purpose works. */
		public Duration validity() {
			return validity;
		}

	}

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@Column(nullable = false, length = 20)
	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	private Purpose purpose;

	@Column(nullable = false, length = 64)
	private String tokenHash;

	@Column(nullable = false)
	private LocalDateTime expiresAt;

	private LocalDateTime usedAt;

	protected UserToken() {
	}

	public UserToken(User user, Purpose purpose, String tokenHash, LocalDateTime expiresAt) {
		this.user = user;
		this.purpose = purpose;
		this.tokenHash = tokenHash;
		this.expiresAt = expiresAt;
	}

	public User getUser() {
		return user;
	}

	public Purpose getPurpose() {
		return purpose;
	}

	public LocalDateTime getExpiresAt() {
		return expiresAt;
	}

	public LocalDateTime getUsedAt() {
		return usedAt;
	}

	/** Not used yet and not expired; a link expires exactly at {@code expiresAt}. */
	public boolean isUsableAt(LocalDateTime now) {
		return usedAt == null && now.isBefore(expiresAt);
	}

	/** Spends the link; it never works again. */
	public void use(LocalDateTime now) {
		if (usedAt == null) {
			usedAt = now;
		}
	}

}
