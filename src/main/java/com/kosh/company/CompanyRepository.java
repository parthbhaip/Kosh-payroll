package com.kosh.company;

import org.springframework.data.jpa.repository.JpaRepository;

/** The single company row ({@link Company#ID}). */
public interface CompanyRepository extends JpaRepository<Company, Long> {

}
