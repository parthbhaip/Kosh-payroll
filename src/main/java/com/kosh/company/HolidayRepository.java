package com.kosh.company;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface HolidayRepository extends JpaRepository<Holiday, Long> {

	/** A location's holidays from {@code from} to {@code to}, both included, by date. */
	List<Holiday> findByLocationIdAndDateBetweenOrderByDate(long locationId, LocalDate from, LocalDate to);

	long countByLocationIdAndDateBetween(long locationId, LocalDate from, LocalDate to);

	Optional<Holiday> findByLocationIdAndDate(long locationId, LocalDate date);

	/** The days the location is closed for a national or festival holiday, from {@code from} to {@code to}. */
	@Query("""
			select h.date from Holiday h
			where h.location.id = :locationId and h.date between :from and :to
			and h.type <> com.kosh.company.HolidayType.OPTIONAL""")
	List<LocalDate> closedDays(long locationId, LocalDate from, LocalDate to);

	@EntityGraph(attributePaths = "location")
	Optional<Holiday> findWithLocationById(long id);

}
