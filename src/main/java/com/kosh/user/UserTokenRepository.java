package com.kosh.user;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.kosh.user.UserToken.Purpose;

public interface UserTokenRepository extends JpaRepository<UserToken, Long> {

	/** The link with this token hash, with its user. */
	@EntityGraph(attributePaths = "user")
	Optional<UserToken> findByTokenHashAndPurpose(String tokenHash, Purpose purpose);

	/** Links of one purpose that have not been used yet (expired ones included). */
	List<UserToken> findByUserAndPurposeAndUsedAtIsNull(User user, Purpose purpose);

	/** Every link of the user that has not been used yet, whatever its purpose. */
	List<UserToken> findByUserAndUsedAtIsNull(User user);

	/** The most recent link of one purpose, for "invite sent" on the user page. */
	Optional<UserToken> findFirstByUserAndPurposeOrderByIdDesc(User user, Purpose purpose);

}
