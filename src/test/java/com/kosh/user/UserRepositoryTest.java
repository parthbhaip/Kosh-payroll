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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;

import com.kosh.config.AppProperties;
import com.kosh.config.JpaAuditingConfig;
import com.kosh.config.TimeConfig;
import com.kosh.user.UserToken.Purpose;

/**
 * The users and user_tokens tables as Flyway creates them (H2 in MySQL mode from the test profile, not a replacement database), with
 * Hibernate validating the entity against that schema.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ActiveProfiles("test")
class UserRepositoryTest {

	/** The fixed test clock: 5 Oct 2026, 10:00 in Kolkata. */
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 5, 10, 0);

	private final UserRepository users;

	private final UserTokenRepository tokens;

	private final TestEntityManager em;

	@Autowired
	UserRepositoryTest(UserRepository users, UserTokenRepository tokens, TestEntityManager em) {
		this.users = users;
		this.tokens = tokens;
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
		user.recordLoginFailure(NOW);
		assertThat(user.recordLoginFailure(NOW)).isFalse();
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

	@Test
	void searchMatchesNameOrEmailInAnyCaseFiltersByRoleAndPages() {
		users.saveAndFlush(new User("meera.deshpande@search.kosh.test", "Meera Deshpande", List.of(Role.HR, Role.EMPLOYEE)));
		users.saveAndFlush(new User("nikhil.pawar@search.kosh.test", "Nikhil Pawar", List.of(Role.FINANCE, Role.EMPLOYEE)));
		users.saveAndFlush(new User("odd_name%@search.kosh.test", "Odd! Name", List.of(Role.EMPLOYEE)));
		em.clear();
		PageRequest byName = PageRequest.of(0, 25, Sort.by("displayName"));

		assertThat(names(users.search("search.kosh", null, byName))).containsExactly("Meera Deshpande", "Nikhil Pawar",
				"Odd! Name");
		assertThat(names(users.search("DESHPANDE", null, byName))).containsExactly("Meera Deshpande");
		assertThat(names(users.search("search.kosh", Role.FINANCE, byName))).containsExactly("Nikhil Pawar");
		assertThat(names(users.search("search.kosh", Role.EMPLOYEE, byName))).hasSize(3);
		// %, _ and the escape character match only themselves.
		assertThat(names(users.search("_name%", null, byName))).containsExactly("Odd! Name");
		assertThat(names(users.search("odd!", null, byName))).containsExactly("Odd! Name");
		assertThat(names(users.search("%", null, byName))).containsExactly("Odd! Name");
		assertThat(names(users.search("a_d", null, byName))).as("_ is not a wildcard").isEmpty();

		Page<User> second = users.search("search.kosh", null, PageRequest.of(1, 2, Sort.by("displayName")));
		assertThat(second.getTotalElements()).isEqualTo(3);
		assertThat(names(second)).containsExactly("Odd! Name");
	}

	@Test
	void rolesForAPageLoadInOneQuery() {
		Long id = users.saveAndFlush(new User("roles@search.kosh.test", "Rina Joshi", List.of(Role.PAYROLL, Role.EMPLOYEE)))
			.getId();
		em.clear();

		List<User> loaded = users.findWithRolesByIdIn(List.of(id));

		assertThat(em.getEntityManager().getEntityManagerFactory().getPersistenceUnitUtil().isLoaded(loaded.get(0), "roles"))
			.isTrue();
		assertThat(loaded.get(0).getRoles()).containsExactlyInAnyOrder(Role.PAYROLL, Role.EMPLOYEE);
	}

	@Test
	void onlyEnabledAdminsWithAPasswordCountAsActive() {
		User asha = adminWithPassword("asha@admins.kosh.test");
		User invited = users.saveAndFlush(new User("invited@admins.kosh.test", "Invited", List.of(Role.ADMIN)));
		adminWithPassword("disabled@admins.kosh.test").setEnabled(false);
		User hr = users.save(new User("hr@admins.kosh.test", "HR", List.of(Role.HR, Role.EMPLOYEE)));
		hr.setPasswordHash("{bcrypt}x");
		users.flush();
		long before = users.countOtherActiveAdmins(-1L);

		assertThat(users.countOtherActiveAdmins(asha.getId())).isEqualTo(before - 1);
		assertThat(users.countOtherActiveAdmins(invited.getId())).isEqualTo(before);

		User second = adminWithPassword("second@admins.kosh.test");
		assertThat(users.countOtherActiveAdmins(asha.getId())).isEqualTo(before);
		assertThat(users.countOtherActiveAdmins(second.getId())).isEqualTo(before);
	}

	@Test
	void tokensAreFoundByHashAndPurposeAndSpentOnesDropOutOfTheOpenList() {
		User user = users.saveAndFlush(new User("token@repo.kosh.test", "Token User", List.of(Role.EMPLOYEE)));
		UserToken first = tokens.saveAndFlush(new UserToken(user, Purpose.INVITE, "a".repeat(64), NOW.plusHours(48)));
		tokens.saveAndFlush(new UserToken(user, Purpose.INVITE, "b".repeat(64), NOW.plusHours(48)));
		first.use(NOW);
		tokens.flush();
		em.clear();

		UserToken found = tokens.findByTokenHashAndPurpose("a".repeat(64), Purpose.INVITE).orElseThrow();
		assertThat(found.getUsedAt()).isEqualTo(NOW);
		assertThat(found.getUser().getEmail()).isEqualTo("token@repo.kosh.test");
		assertThat(found.isUsableAt(NOW)).isFalse();
		User reloaded = users.findById(user.getId()).orElseThrow();
		assertThat(tokens.findByUserAndPurposeAndUsedAtIsNull(reloaded, Purpose.INVITE)).hasSize(1);
		assertThat(tokens.findFirstByUserAndPurposeOrderByIdDesc(reloaded, Purpose.INVITE).orElseThrow().isUsableAt(NOW))
			.isTrue();
		assertThatThrownBy(() -> tokens.saveAndFlush(new UserToken(reloaded, Purpose.INVITE, "b".repeat(64), NOW)))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void aLinkWorksUntilItsExpiryAndOnlyOnce() {
		User user = new User("expiry@repo.kosh.test", "Expiry", List.of(Role.EMPLOYEE));
		UserToken link = new UserToken(user, Purpose.INVITE, "c".repeat(64), NOW.plusHours(48));

		assertThat(link.isUsableAt(NOW.plusHours(48).minusNanos(1000))).isTrue();
		assertThat(link.isUsableAt(NOW.plusHours(48))).isFalse();
		link.use(NOW);
		link.use(NOW.plusMinutes(5));
		assertThat(link.getUsedAt()).isEqualTo(NOW);
		assertThat(link.isUsableAt(NOW)).isFalse();
	}

	private User adminWithPassword(String email) {
		User admin = new User(email, "Admin " + email, List.of(Role.ADMIN, Role.EMPLOYEE));
		admin.setPasswordHash("{bcrypt}x");
		return users.saveAndFlush(admin);
	}

	private static List<String> names(Page<User> page) {
		return page.map(User::getDisplayName).getContent();
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
