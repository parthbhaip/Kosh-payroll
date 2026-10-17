package com.kosh.statutory;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** Total income above the previous slab and up to {@code upTo} (null: no upper limit) is taxed at {@code rate} per cent. */
@Embeddable
public record TaxSlab(@Column(name = "income_up_to") BigDecimal upTo, @Column(nullable = false) BigDecimal rate) {

	public TaxSlab {
		upTo = Scales.money(upTo);
		rate = Scales.rate(rate);
	}

}
