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

import com.kosh.common.IndianState;

/** A version of one state's professional tax ({@link RuleFamily#PT}); the scope is the state code. */
@Entity
@Table(name = "pt_rules")
public class PtRuleVersion extends RuleVersion {

	@Column(nullable = false)
	private BigDecimal annualCap;

	/** Loaded for every version a query returned at once (one query), not one query per version. */
	@ElementCollection
	@CollectionTable(name = "pt_slabs", joinColumns = @JoinColumn(name = "pt_rule_id"))
	@OrderColumn(name = "line_no")
	@Fetch(FetchMode.SUBSELECT)
	private List<PtSlab> slabs = new ArrayList<>();

	protected PtRuleVersion() {
	}

	PtRuleVersion(IndianState state, PtRules rules, LocalDate effectiveFrom, String sourceNote, LocalDate verifiedOn) {
		super(RuleFamily.PT, state.name(), effectiveFrom, sourceNote, verifiedOn);
		this.annualCap = rules.annualCap();
		this.slabs = new ArrayList<>(rules.slabs());
	}

	@Override
	public PtRules rules() {
		return new PtRules(annualCap, slabs);
	}

}
