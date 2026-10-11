package com.kosh.company;

import java.time.LocalDate;

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
import com.kosh.org.WorkLocation;

/**
 * One holiday at one work location; at most one a day per location. {@code dateToCheck}: copied from the previous
 * year and not yet confirmed by HR. Changed only through {@link HolidayService}.
 */
@Entity
@Table(name = "holidays")
public class Holiday extends BaseEntity {

	/** The entity type in the audit log. */
	public static final String AUDIT_TYPE = "Holiday";

	public static final int MAX_NAME = 100;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "location_id", nullable = false)
	private WorkLocation location;

	@Column(name = "holiday_date", nullable = false)
	private LocalDate date;

	@Column(nullable = false, length = MAX_NAME)
	private String name;

	@Column(name = "holiday_type", nullable = false, length = 10)
	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	private HolidayType type;

	@Column(name = "date_to_check", nullable = false)
	private boolean dateToCheck;

	protected Holiday() {
	}

	Holiday(WorkLocation location, LocalDate date, String name, HolidayType type, boolean dateToCheck) {
		this.location = location;
		set(date, name, type);
		this.dateToCheck = dateToCheck;
	}

	public WorkLocation getLocation() {
		return location;
	}

	public LocalDate getDate() {
		return date;
	}

	public String getName() {
		return name;
	}

	public HolidayType getType() {
		return type;
	}

	public boolean isDateToCheck() {
		return dateToCheck;
	}

	/** Saving the holiday confirms its date. */
	void set(LocalDate date, String name, HolidayType type) {
		this.date = date;
		this.name = name;
		this.type = type;
		this.dateToCheck = false;
	}

}
