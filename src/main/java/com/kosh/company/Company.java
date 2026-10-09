package com.kosh.company;

import java.util.Arrays;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.kosh.common.BaseEntity;
import com.kosh.common.EncryptedStringConverter;
import com.kosh.common.IndianState;
import com.kosh.statutory.PfPolicy;

/**
 * The employer: one row, id {@value #ID}, created by migration V4 with the default pay settings. Read and changed as
 * {@link CompanyProfile} and {@link PaySettings} values through {@link CompanyService}. The PAN is encrypted at rest.
 */
@Entity
@Table(name = "company")
public class Company extends BaseEntity {

	public static final long ID = 1L;

	/** Entity type in the audit log. */
	public static final String AUDIT_TYPE = "Company";

	private static final String CITY_SEPARATOR = ", ";

	@Column(length = 150)
	private String legalName;

	@Column(length = 80)
	private String brandName;

	@Column(name = "address_line1", length = 150)
	private String addressLine1;

	@Column(name = "address_line2", length = 150)
	private String addressLine2;

	@Column(length = 60)
	private String city;

	@Column(name = "state_code", length = 2)
	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	private IndianState state;

	@Column(length = 6)
	private String pincode;

	@Convert(converter = EncryptedStringConverter.class)
	@Column(length = 255)
	private String pan;

	@Column(length = 10)
	private String tan;

	@Column(length = 15)
	private String pfEstablishmentCode;

	@Column(length = 17)
	private String esiEmployerCode;

	@Column(length = 12)
	private String ptRegistrationNumber;

	@Column(length = 120)
	private String signatoryName;

	@Column(length = 80)
	private String signatoryDesignation;

	@Column(length = 100)
	private String logoPath;

	@Column(nullable = false, length = 20)
	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	private ProrationMethod prorationMethod;

	@Column(nullable = false)
	private int payDay;

	@Column(nullable = false)
	private int attendanceCutoffDay;

	@Column(nullable = false, length = 10)
	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	private PfPolicy defaultPfPolicy;

	@Column(nullable = false)
	private boolean gratuityInCtc;

	@Column(nullable = false)
	private boolean pfChargesInCtc;

	@Column(nullable = false)
	private int leaveEncashmentDivisor;

	@Column(nullable = false, length = 500)
	private String metroCities;

	@Column(nullable = false)
	private int pfDueDay;

	@Column(nullable = false)
	private int esiDueDay;

	@Column(nullable = false)
	private int tdsDueDay;

	@Column(nullable = false)
	private int ptDueDay;

	protected Company() {
	}

	public CompanyProfile profile() {
		return new CompanyProfile(legalName, brandName, addressLine1, addressLine2, city, state, pincode, pan, tan,
				pfEstablishmentCode, esiEmployerCode, ptRegistrationNumber, signatoryName, signatoryDesignation);
	}

	void setProfile(CompanyProfile profile) {
		legalName = profile.legalName();
		brandName = profile.brandName();
		addressLine1 = profile.addressLine1();
		addressLine2 = profile.addressLine2();
		city = profile.city();
		state = profile.state();
		pincode = profile.pincode();
		pan = profile.pan();
		tan = profile.tan();
		pfEstablishmentCode = profile.pfEstablishmentCode();
		esiEmployerCode = profile.esiEmployerCode();
		ptRegistrationNumber = profile.ptRegistrationNumber();
		signatoryName = profile.signatoryName();
		signatoryDesignation = profile.signatoryDesignation();
	}

	public PaySettings paySettings() {
		List<String> cities = Arrays.stream(metroCities.split(",")).map(String::strip).toList();
		return new PaySettings(prorationMethod, payDay, attendanceCutoffDay, defaultPfPolicy, gratuityInCtc,
				pfChargesInCtc, leaveEncashmentDivisor, cities, pfDueDay, esiDueDay, tdsDueDay, ptDueDay);
	}

	void setPaySettings(PaySettings settings) {
		prorationMethod = settings.prorationMethod();
		payDay = settings.payDay();
		attendanceCutoffDay = settings.attendanceCutoffDay();
		defaultPfPolicy = settings.defaultPfPolicy();
		gratuityInCtc = settings.gratuityInCtc();
		pfChargesInCtc = settings.pfChargesInCtc();
		leaveEncashmentDivisor = settings.leaveEncashmentDivisor();
		metroCities = String.join(CITY_SEPARATOR, settings.metroCities());
		pfDueDay = settings.pfDueDay();
		esiDueDay = settings.esiDueDay();
		tdsDueDay = settings.tdsDueDay();
		ptDueDay = settings.ptDueDay();
	}

	/** Where the logo is stored ({@code FileStorage} path), or null when there is none. */
	public String getLogoPath() {
		return logoPath;
	}

	void setLogoPath(String logoPath) {
		this.logoPath = logoPath;
	}

}
