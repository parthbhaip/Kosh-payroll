package com.kosh.org;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** A job title, printed on payslips and letters. */
@Entity
@Table(name = "designations")
public class Designation extends OrgItem {

}
