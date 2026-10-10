package com.kosh.org;

import java.util.List;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.EntityGraph;

public interface DepartmentRepository extends OrgItemRepository<Department> {

	/** The list page shows each head's name: loaded in the same query. */
	@Override
	@EntityGraph(attributePaths = "head")
	List<Department> findAll(Sort sort);

}
