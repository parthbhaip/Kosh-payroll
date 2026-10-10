package com.kosh.org;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

/** Look-ups every part of the organisation structure shares. Codes are stored upper-case, so the code match is exact. */
@NoRepositoryBean
public interface OrgItemRepository<T extends OrgItem> extends JpaRepository<T, Long> {

	Optional<T> findByCode(String code);

	Optional<T> findFirstByNameIgnoreCase(String name);

}
