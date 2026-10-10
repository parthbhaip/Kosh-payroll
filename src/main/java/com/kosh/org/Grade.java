package com.kosh.org;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** A grade: jobs of a similar level grouped together. */
@Entity
@Table(name = "grades")
public class Grade extends OrgItem {

}
