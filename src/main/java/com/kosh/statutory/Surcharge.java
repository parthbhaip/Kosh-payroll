package com.kosh.statutory;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** Surcharge of {@code rate} per cent of tax once total income is above {@code incomeAbove}; the highest one crossed applies. */
@Embeddable
public record Surcharge(@Column(nullable = false) BigDecimal incomeAbove, @Column(nullable = false) BigDecimal rate) {

	public Surcharge {
		incomeAbove = Scales.money(incomeAbove);
		rate = Scales.rate(rate);
	}

}
