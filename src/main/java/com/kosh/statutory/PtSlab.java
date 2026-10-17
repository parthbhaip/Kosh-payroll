package com.kosh.statutory;

import java.math.BigDecimal;
import java.time.Month;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.kosh.common.Gender;

/**
 * One professional tax slab: for {@code gender}, gross monthly salary above the previous slab and up to {@code upTo}
 * (null: no upper limit) pays {@code monthly}, or {@code february} in February.
 */
@Embeddable
public record PtSlab(
		@Column(nullable = false, length = 6) @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) Gender gender,
		@Column(name = "salary_up_to") BigDecimal upTo,
		@Column(name = "monthly_amount", nullable = false) BigDecimal monthly,
		@Column(name = "february_amount", nullable = false) BigDecimal february) {

	public PtSlab {
		upTo = Scales.money(upTo);
		monthly = Scales.money(monthly);
		february = Scales.money(february);
	}

	/** The amount for a salary month: the February amount in February, else the monthly one. */
	public BigDecimal amountFor(Month month) {
		return (month == Month.FEBRUARY) ? february : monthly;
	}

}
