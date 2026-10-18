package com.kosh.employee;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.kosh.common.HmacHasher;
import com.kosh.common.Text;
import com.kosh.user.User;

/**
 * Employees, with their organisation details fetched in the same query wherever a page shows them, so a list never
 * loads them one row at a time.
 */
public interface EmployeeRepository extends JpaRepository<Employee, Long>, JpaSpecificationExecutor<Employee> {

	/** Everything a profile shows about one employee, in one query: organisation, manager and sign-in account. */
	@EntityGraph(attributePaths = { "department", "designation", "grade", "location", "costCentre", "reportingManager",
			"user" })
	Optional<Employee> findWithDetailsById(Long id);

	/**
	 * A page of the employees {@code spec} matches (null for everyone), with department, designation, location and
	 * reporting manager in the same query: the shape of a list page such as the directory.
	 */
	@Override
	@EntityGraph(attributePaths = { "department", "designation", "location", "reportingManager" })
	Page<Employee> findAll(Specification<Employee> spec, Pageable pageable);

	/**
	 * A manager's current team: the people who report to them directly and have not left, by name, with department,
	 * designation and location loaded.
	 */
	@Query("""
			select e from Employee e join fetch e.department join fetch e.designation join fetch e.location
			where e.reportingManager.id = :managerId and e.status <> com.kosh.employee.EmployeeStatus.EXITED
			order by e.firstName, e.lastName, e.id""")
	List<Employee> findTeam(Long managerId);

	/** Internal: exact match on an already normalised e-mail. Call {@link #findByUserEmail}. */
	@EntityGraph(attributePaths = { "department", "designation", "grade", "location", "costCentre", "reportingManager",
			"user" })
	Optional<Employee> findWithDetailsByUserEmail(String email);

	/** The employee whose sign-in account has this e-mail (any case), with the profile details loaded. */
	default Optional<Employee> findByUserEmail(String email) {
		String normalized = User.normalizeEmail(email);
		return normalized == null || normalized.isEmpty() ? Optional.empty() : findWithDetailsByUserEmail(normalized);
	}

	/**
	 * Everyone who has not left, by name: the people an employee can report to. Organisation details are not loaded.
	 * ponytail: the whole list for a select; a search picker when headcount reaches the thousands.
	 */
	@Query("""
			select e from Employee e where e.status <> com.kosh.employee.EmployeeStatus.EXITED
			order by e.firstName, e.lastName, e.id""")
	List<Employee> findCurrent();

	/** Whoever has this personal e-mail (stored lower case). */
	List<Employee> findByPersonalEmail(String personalEmail);

	Optional<Employee> findByUan(String uan);

	Optional<Employee> findByEsiIpNumber(String esiIpNumber);

	/** Internal: by look-up hash. Call {@link #findByPan}, which tidies and hashes the PAN as storing it does. */
	Optional<Employee> findByPanHash(String panHash);

	/** The employee with this PAN, typed in any case and with spaces. */
	default Optional<Employee> findByPan(String pan, HmacHasher hasher) {
		String normalized = Text.code(pan);
		return (normalized == null) ? Optional.empty() : findByPanHash(hasher.hash(normalized));
	}

	/** Internal: moves the code counter on and keeps its row locked until the transaction ends. Call {@link #nextCode}. */
	@Modifying
	@Query(value = "update employee_code_counter set last_number = last_number + 1 where id = 1", nativeQuery = true)
	void advanceCodeCounter();

	/** Internal: the number in the last code handed out. */
	@Query(value = "select last_number from employee_code_counter where id = 1", nativeQuery = true)
	int lastCodeNumber();

	/**
	 * The code for a new employee: KSH-0001, KSH-0002 and so on. Call it in the transaction that saves the employee:
	 * concurrent adds wait for each other on the counter row, so no code is handed out twice, and an add that rolls
	 * back gives its number back.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	default String nextCode() {
		advanceCodeCounter();
		return Employee.code(lastCodeNumber());
	}

}
