package com.kosh.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class UserTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 5, 10, 0);

	@Test
	void emailIsTrimmedAndLowerCased() {
		assertThat(User.normalizeEmail("  Admin@Kosh.TEST\n")).isEqualTo("admin@kosh.test");
		assertThat(User.normalizeEmail(null)).isNull();
		assertThat(new User(" HR@kosh.test", "  Sunita Patil ", List.of(Role.HR)).getEmail()).isEqualTo("hr@kosh.test");
		assertThat(new User("hr@kosh.test", "  Sunita Patil ", List.of(Role.HR)).getDisplayName())
			.isEqualTo("Sunita Patil");
	}

	@Test
	void emailDisplayNameAndAtLeastOneRoleAreRequired() {
		assertThatThrownBy(() -> new User("  ", "Admin", List.of(Role.ADMIN)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new User(null, "Admin", List.of(Role.ADMIN)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new User("admin@kosh.test", " ", List.of(Role.ADMIN)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new User("admin@kosh.test", "Admin", null)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new User("admin@kosh.test", "Admin", List.of()))
			.isInstanceOf(IllegalArgumentException.class);

		User user = new User("admin@kosh.test", "Admin", List.of(Role.ADMIN, Role.EMPLOYEE));
		assertThatThrownBy(() -> user.setRoles(Set.of())).isInstanceOf(IllegalArgumentException.class);
		assertThat(user.getRoles()).containsExactlyInAnyOrder(Role.ADMIN, Role.EMPLOYEE);
	}

	@Test
	void setRolesReplacesTheWholeSetAndTheViewIsReadOnly() {
		User user = new User("payroll@kosh.test", "Anil Deshmukh", List.of(Role.PAYROLL, Role.EMPLOYEE));

		user.setRoles(Set.of(Role.FINANCE, Role.EMPLOYEE));

		assertThat(user.getRoles()).containsExactlyInAnyOrder(Role.FINANCE, Role.EMPLOYEE);
		assertThat(user.hasRole(Role.FINANCE)).isTrue();
		assertThat(user.hasRole(Role.PAYROLL)).isFalse();
		assertThatThrownBy(() -> user.getRoles().add(Role.ADMIN)).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void lockHoldsUntilItsEndTime() {
		User user = new User("employee@kosh.test", "Kavya Pawar", List.of(Role.EMPLOYEE));
		assertThat(user.isLockedAt(NOW)).isFalse();

		user.lockUntil(NOW.plusMinutes(15));

		assertThat(user.isLockedAt(NOW)).isTrue();
		assertThat(user.isLockedAt(NOW.plusMinutes(15).minusNanos(1000))).isTrue();
		assertThat(user.isLockedAt(NOW.plusMinutes(15))).isFalse();
	}

	@Test
	void successfulSignInClearsFailuresAndLock() {
		User user = new User("employee@kosh.test", "Kavya Pawar", List.of(Role.EMPLOYEE));
		user.recordLoginFailure(NOW);
		user.recordLoginFailure(NOW);
		user.lockUntil(NOW.plusMinutes(15));

		user.recordLoginSuccess(NOW);

		assertThat(user.getFailedLoginCount()).isZero();
		assertThat(user.isLockedAt(NOW)).isFalse();
		assertThat(user.getLastLoginAt()).isEqualTo(NOW);
	}

	@Test
	void theFifthWrongPasswordInARowLocksForFifteenMinutesAndStartsTheCountAgain() {
		User user = new User("employee@kosh.test", "Kavya Pawar", List.of(Role.EMPLOYEE));

		for (int failure = 1; failure <= 4; failure++) {
			assertThat(user.recordLoginFailure(NOW)).as("failure %d", failure).isFalse();
			assertThat(user.getFailedLoginCount()).isEqualTo(failure);
		}
		assertThat(user.isLockedAt(NOW)).isFalse();

		assertThat(user.recordLoginFailure(NOW)).isTrue();
		assertThat(user.getLockedUntil()).isEqualTo(NOW.plusMinutes(15));
		assertThat(user.isLockedAt(NOW.plusMinutes(14))).isTrue();
		assertThat(user.isLockedAt(NOW.plusMinutes(15))).isFalse();
		// After the lock the owner has five tries again.
		assertThat(user.getFailedLoginCount()).isZero();
		assertThat(User.MAX_FAILED_LOGINS).isEqualTo(5);
	}

	@Test
	void clearingFailuresLiftsTheLockButKeepsTheLastSignIn() {
		User user = new User("employee@kosh.test", "Kavya Pawar", List.of(Role.EMPLOYEE));
		user.recordLoginSuccess(NOW.minusDays(1));
		for (int failure = 0; failure < 5; failure++) {
			user.recordLoginFailure(NOW);
		}
		user.recordLoginFailure(NOW);

		user.clearLoginFailures();

		assertThat(user.getFailedLoginCount()).isZero();
		assertThat(user.getLockedUntil()).isNull();
		assertThat(user.getLastLoginAt()).isEqualTo(NOW.minusDays(1));
	}

}
