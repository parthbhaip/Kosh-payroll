package com.kosh.audit;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One recorded event: who did what to which record, when and from where, with the changed fields before and after
 * (JSON, sensitive values already masked). Written once and never changed, so it has no version or update time and
 * does not extend {@link com.kosh.common.BaseEntity}. Create events through {@link AuditService}.
 */
@Entity
@Table(name = "audit_events")
@Immutable
public class AuditEvent {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private LocalDateTime occurredAt;

	@Column(length = 254)
	private String actorEmail;

	@Column(nullable = false, length = 120)
	private String actorName;

	@Column(nullable = false, length = 40)
	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	private AuditAction action;

	@Column(length = 40)
	private String entityType;

	private Long entityId;

	@Column(nullable = false, length = 300)
	private String summary;

	@Column(length = 4000)
	private String beforeJson;

	@Column(length = 4000)
	private String afterJson;

	@Column(length = 45)
	private String ipAddress;

	protected AuditEvent() {
	}

	AuditEvent(LocalDateTime occurredAt, String actorEmail, String actorName, AuditAction action, String entityType,
			Long entityId, String summary, String beforeJson, String afterJson, String ipAddress) {
		this.occurredAt = occurredAt;
		this.actorEmail = actorEmail;
		this.actorName = actorName;
		this.action = action;
		this.entityType = entityType;
		this.entityId = entityId;
		this.summary = summary;
		this.beforeJson = beforeJson;
		this.afterJson = afterJson;
		this.ipAddress = ipAddress;
	}

	public Long getId() {
		return id;
	}

	public LocalDateTime getOccurredAt() {
		return occurredAt;
	}

	public String getActorEmail() {
		return actorEmail;
	}

	public String getActorName() {
		return actorName;
	}

	public AuditAction getAction() {
		return action;
	}

	public String getEntityType() {
		return entityType;
	}

	public Long getEntityId() {
		return entityId;
	}

	public String getSummary() {
		return summary;
	}

	public String getBeforeJson() {
		return beforeJson;
	}

	public String getAfterJson() {
		return afterJson;
	}

	public String getIpAddress() {
		return ipAddress;
	}

}
