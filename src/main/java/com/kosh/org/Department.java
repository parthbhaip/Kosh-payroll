package com.kosh.org;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import com.kosh.user.User;

/**
 * A department. Its head is a person with a Kosh account (employees have no table before T15), optional.
 */
@Entity
@Table(name = "departments")
public class Department extends OrgItem {

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "head_user_id")
	private User head;

	public User getHead() {
		return head;
	}

	void setHead(User head) {
		this.head = head;
	}

}
