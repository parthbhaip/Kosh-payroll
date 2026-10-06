package com.kosh.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import jakarta.persistence.PersistenceException;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import com.kosh.config.AppProperties;
import com.kosh.config.JpaAuditingConfig;
import com.kosh.config.TimeConfig;

/**
 * The users tables as Flyway creates them (H2 in MySQL mode from the test profile, not a replacement database), with
 * Hibernate validating the entity against that schema.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ActiveProfiles("test")
class UserRepositoryTest {

	/** The fixed test clock: 5 Oct 2026, 10:00 in Kolkata. */
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 5, 10, 0);

	private final UserRepository users;

	private final TestEntityManager em;

	@Autowired
	UserRepositoryTest(UserRepository users, TestEntityManager em) {
		this.users = users;
		this.em = em;
	}

	@Test
	void newAccountIsStoredLowerCaseWithItsRolesAndSignInDefaults() {
		Long id = users.saveAndFlush(new User("  Priya.Kulkarni@repo.Kosh.TEST ", "Priya Kulkarni",
				List.of(Role.HR, Role.EMPLOYEE)))
			.getId();
		em.clear();

		assertThat(storedEmail(id)).isEqualTo("priya.kulkarni@repo.kosh.test");
		User loaded = users.findById(id).orElseThrow();
		assertThat(loaded.getEmail()).isEqualTo("priya.kulkarni@repo.kosh.test");
		assertThat(loaded.getDisplayName()).isEqualTo("Priya Kulkarni");
		assertThat(loaded.getRoles()).containsExactlyInAnyOrder(Role.HR, Role.EMPLOYEE);
		assertThat(loaded.getPasswordHash()).isNull();
		assertThat(loaded.isEnabled()).isTrue();
		assertThat(loaded.getFailedLoginCount()).isZero();
		assertThat(loaded.getLockedUntil()).isNull();
		assertThat(loaded.getLastLoginAt()).isNull();
		assertThat(loaded.getCreatedAt()).isEqualTo(NOW);
		assertThat(loaded.getVersion()).isZero();
	}

	@Test
	void lookupIgnoresCaseAndSurroundingSpacesAndLoadsRolesInTheSameQuery() {
		users.saveAndFlush(new User("finance@repo.kosh.test", "Meera Joshi", List.of(Role.FINANCE, Role.EMPLOYEE)));
		em.clear();

		User found = users.findByEmailIgnoreCase(" FINANCE@repo.Kosh.Test\t").orElseThrow();

		assertThat(em.getEntityManager().getEntityManagerFactory().getPersistenceUnitUtil().isLoaded(found, "roles"))
			.isTrue();
		assertThat(found.getRoles()).containsExactlyInAnyOrder(Role.FINANCE, Role.EMPLOYEE);
		assertThat(users.findByEmailIgnoreCase("finance@repo.kosh.test")).contains(found);
		assertThat(users.existsByEmailIgnoreCase("Finance@repo.KOSH.test")).isTrue();
	}

	@Test
	void unknownBlankOrMissingEmailFindsNobody() {
		users.saveAndFlush(new User("hr@repo.kosh.test", "Sunita Patil", List.of(Role.HR)));

		assertThat(users.findByEmailIgnoreCase("payroll@repo.kosh.test")).isEmpty();
		assertThat(users.findByEmailIgnoreCase("   ")).isEmpty();
		assertThat(users.findByEmailIgnoreCase(null)).isEmpty();
		assertThat(users.existsByEmailIgnoreCase("payroll@repo.kosh.test")).isFalse();
		assertThat(users.existsByEmailIgnoreCase("")).isFalse();
		assertThat(users.existsByEmailIgnoreCase(null)).isFalse();
	}

	@Test
	void sameEmailInAnotherCaseIsRejectedByTheUniqueKey() {
		users.saveAndFlush(new User("hr@repo.kosh.test", "Sunita Patil", List.of(Role.HR)));

		User sameEmail = new User("HR@repo.Kosh.test", "Someone Else", List.of(Role.EMPLOYEE));

		assertThatThrownBy(() -> users.saveAndFlush(sameEmail)).isInstanceOf(DataIntegrityViolationException.class);
	}

	/** H2 only: on MySQL the ci collation makes this CHECK always pass, so User.normalizeEmail is the guard there. */
	@Test
	void databaseRejectsAnUpperCaseEmail() {
		assertThatThrownBy(() -> em.getEntityManager()
			.createNativeQuery("INSERT INTO users (email, display_name, created_at, updated_at, version)"
					+ " VALUES ('Admin@repo.Kosh.test', 'Admin', ?1, ?1, 0)")
			.setParameter(1, NOW)
			.executeUpdate()).isInstanceOf(PersistenceException.class);
	}

	@Test
	void databaseRejectsARoleThatIsNotAKoshRole() {
		Long id = users.saveAndFlush(new User("admin@repo.kosh.test", "Admin", List.of(Role.ADMIN))).getId();

		assertThatThrownBy(() -> em.getEntityManager()
			.createNativeQuery("INSERT INTO user_roles (user_id, role) VALUES (?1, 'SUPERUSER')")
			.setParameter(1, id)
			.executeUpdate()).isInstanceOf(PersistenceException.class);
	}

	@Test
	void roleChangesAndSignInStateAreSaved() {
		Long id = users.saveAndFlush(new User("manager@repo.kosh.test", "Rahul Shinde", List.of(Role.EMPLOYEE)))
			.getId();
		em.clear();

		User user = users.findById(id).orElseThrow();
		user.setRoles(Set.of(Role.MANAGER, Role.EMPLOYEE));
		user.setPasswordHash("{bcrypt}" + "h".repeat(60));
		user.setEnabled(false);
		user.recordLoginFailure();
		assertThat(user.recordLoginFailure()).isEqualTo(2);
		user.lockUntil(NOW.plusMinutes(15));
		users.flush();
		em.clear();

		User changed = users.findByEmailIgnoreCase("manager@repo.kosh.test").orElseThrow();
		assertThat(changed.getRoles()).containsExactlyInAnyOrder(Role.MANAGER, Role.EMPLOYEE);
		assertThat(changed.getPasswordHash()).startsWith("{bcrypt}");
		assertThat(changed.isEnabled()).isFalse();
		assertThat(changed.getFailedLoginCount()).isEqualTo(2);
		assertThat(changed.getLockedUntil()).isEqualTo(NOW.plusMinutes(15));
		assertThat(changed.getVersion()).isEqualTo(1L);

		changed.setRoles(Set.of(Role.EMPLOYEE));
		changed.recordLoginSuccess(NOW);
		users.flush();
		em.clear();

		User signedIn = users.findById(id).orElseThrow();
		assertThat(signedIn.getRoles()).containsExactly(Role.EMPLOYEE);
		assertThat(signedIn.getFailedLoginCount()).isZero();
		assertThat(signedIn.getLockedUntil()).isNull();
		assertThat(signedIn.getLastLoginAt()).isEqualTo(NOW);
	}

	private String storedEmail(Long id) {
		return (String) em.getEntityManager()
			.createNativeQuery("SELECT email FROM users WHERE id = ?1")
			.setParameter(1, id)
			.getSingleResult();
	}

	@TestConfiguration(proxyBeanMethods = false)
	@EnableConfigurationProperties(AppProperties.class)
	@Import({ TimeConfig.class, JpaAuditingConfig.class })
	static class Config {

	}

}
