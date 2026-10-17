package com.kosh.statutory;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface RuleVersionRepository extends JpaRepository<RuleVersion, Long> {

	/** Every family's version in force on {@code date}: at most one per family and scope. */
	@Query("select v from RuleVersion v where v.effectiveFrom <= ?1 and (v.effectiveTo is null or v.effectiveTo >= ?1)")
	List<RuleVersion> inForceOn(LocalDate date);

	/** One family's versions in one scope, latest first. */
	List<RuleVersion> findByFamilyAndScopeOrderByEffectiveFromDesc(RuleFamily family, String scope);

}
