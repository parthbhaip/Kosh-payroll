package com.kosh.company;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface WorkWeekRepository extends JpaRepository<WorkWeek, Long> {

	Optional<WorkWeek> findByLocationId(long locationId);

}
