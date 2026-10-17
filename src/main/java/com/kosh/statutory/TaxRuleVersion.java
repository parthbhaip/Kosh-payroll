package com.kosh.statutory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;

/** A version of one income-tax regime ({@link RuleFamily#NEW_TAX} or {@link RuleFamily#OLD_TAX}). */
@Entity
@Table(name = "tax_regime_rules")
public class TaxRuleVersion extends RuleVersion {

	@Column(nullable = false)
	private BigDecimal standardDeduction;

	@Column(nullable = false)
	private BigDecimal rebateIncomeLimit;

	@Column(nullable = false)
	private BigDecimal rebateMax;

	@Column(nullable = false)
	private BigDecimal cessRate;

	private BigDecimal employerNpsRate;

	/** Slabs and surcharges load for every version a query returned at once (one query each). */
	@ElementCollection
	@CollectionTable(name = "tax_slabs", joinColumns = @JoinColumn(name = "tax_rule_id"))
	@OrderColumn(name = "line_no")
	@Fetch(FetchMode.SUBSELECT)
	private List<TaxSlab> slabs = new ArrayList<>();

	@ElementCollection
	@CollectionTable(name = "tax_surcharges", joinColumns = @JoinColumn(name = "tax_rule_id"))
	@OrderColumn(name = "line_no")
	@Fetch(FetchMode.SUBSELECT)
	private List<Surcharge> surcharges = new ArrayList<>();

	protected TaxRuleVersion() {
	}

	TaxRuleVersion(RuleFamily family, TaxRules rules, LocalDate effectiveFrom, String sourceNote,
			LocalDate verifiedOn) {
		super(family, NATIONAL, effectiveFrom, sourceNote, verifiedOn);
		if (!family.hasTaxSlabs()) {
			throw new IllegalArgumentException("Not an income-tax family: " + family);
		}
		this.standardDeduction = rules.standardDeduction();
		this.rebateIncomeLimit = rules.rebateIncomeLimit();
		this.rebateMax = rules.rebateMax();
		this.cessRate = rules.cessRate();
		this.employerNpsRate = rules.employerNpsRate();
		this.slabs = new ArrayList<>(rules.slabs());
		this.surcharges = new ArrayList<>(rules.surcharges());
	}

	@Override
	public TaxRules rules() {
		return new TaxRules(standardDeduction, rebateIncomeLimit, rebateMax, cessRate, employerNpsRate, slabs,
				surcharges);
	}

}
