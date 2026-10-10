package com.kosh.org;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** A cost centre salary cost is reported against in the accounts. */
@Entity
@Table(name = "cost_centres")
public class CostCentre extends OrgItem {

}
