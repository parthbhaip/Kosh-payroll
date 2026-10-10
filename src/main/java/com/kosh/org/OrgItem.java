package com.kosh.org;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;

import com.kosh.common.BaseEntity;

/**
 * What every part of the organisation structure has: a short unique code (upper case, for import files and reports),
 * a unique name and whether it is active. An inactive item stays on the records that have it but is not offered for
 * new ones. Changed only through {@link OrgService}.
 */
@MappedSuperclass
public abstract class OrgItem extends BaseEntity {

	/** 2 to 10 capital letters, digits or hyphens, starting with a letter or digit: PROD, G3, CC-110. */
	public static final String CODE_FORMAT = "[A-Z0-9][A-Z0-9-]{1,9}";

	public static final int MAX_NAME = 100;

	@Column(nullable = false, length = 10)
	private String code;

	@Column(nullable = false, length = MAX_NAME)
	private String name;

	@Column(nullable = false)
	private boolean active = true;

	public String getCode() {
		return code;
	}

	void setCode(String code) {
		this.code = code;
	}

	public String getName() {
		return name;
	}

	void setName(String name) {
		this.name = name;
	}

	public boolean isActive() {
		return active;
	}

	void setActive(boolean active) {
		this.active = active;
	}

}
