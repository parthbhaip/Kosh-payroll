package com.kosh.org;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.kosh.common.IndianState;
import com.kosh.company.CompanyProfile;

/**
 * Where people work. The state is the key of the professional tax slabs, and {@code ptApplicable} says whether PT is
 * deducted for employees based here at all.
 */
@Entity
@Table(name = "work_locations")
public class WorkLocation extends OrgItem {

	@Column(name = "address_line1", nullable = false, length = CompanyProfile.MAX_ADDRESS_LINE)
	private String addressLine1;

	@Column(name = "address_line2", length = CompanyProfile.MAX_ADDRESS_LINE)
	private String addressLine2;

	@Column(nullable = false, length = CompanyProfile.MAX_CITY)
	private String city;

	@Column(name = "state_code", nullable = false, length = 2)
	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	private IndianState state;

	@Column(nullable = false, length = 6)
	private String pincode;

	@Column(nullable = false)
	private boolean ptApplicable;

	public String getAddressLine1() {
		return addressLine1;
	}

	public String getAddressLine2() {
		return addressLine2;
	}

	public String getCity() {
		return city;
	}

	public IndianState getState() {
		return state;
	}

	public String getPincode() {
		return pincode;
	}

	public boolean isPtApplicable() {
		return ptApplicable;
	}

	void setAddress(String addressLine1, String addressLine2, String city, IndianState state, String pincode) {
		this.addressLine1 = addressLine1;
		this.addressLine2 = addressLine2;
		this.city = city;
		this.state = state;
		this.pincode = pincode;
	}

	void setPtApplicable(boolean ptApplicable) {
		this.ptApplicable = ptApplicable;
	}

}
