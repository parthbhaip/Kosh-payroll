package com.kosh.company;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import com.kosh.common.BaseEntity;
import com.kosh.org.WorkLocation;

/** A work location's weekly offs, once HR has set them; until then {@link WeeklyOffs#DEFAULT} applies. */
@Entity
@Table(name = "work_weeks")
class WorkWeek extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "location_id", nullable = false, unique = true)
	private WorkLocation location;

	@Column(name = "weekly_offs", nullable = false, length = 150)
	private String weeklyOffs;

	protected WorkWeek() {
	}

	WorkWeek(WorkLocation location) {
		this.location = location;
	}

	WeeklyOffs offs() {
		return WeeklyOffs.parse(weeklyOffs);
	}

	void setOffs(WeeklyOffs offs) {
		this.weeklyOffs = offs.text();
	}

}
