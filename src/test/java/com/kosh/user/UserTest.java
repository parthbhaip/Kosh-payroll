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
		user.recordLoginFailure();
		user.recordLoginFailure();
		user.lockUntil(NOW.plusMinutes(15));

		user.recordLoginSuccess(NOW);

		assertThat(user.getFailedLoginCount()).isZero();
		assertThat(user.isLockedAt(NOW)).isFalse();
		assertThat(user.getLastLoginAt()).isEqualTo(NOW);
	}

}
