package com.kosh.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.kosh.user.Role;

class AreaTest {

	@Test
	void pathsBelongToTheAreaOfTheirFirstSegment() {
		assertThat(Area.of("/hr")).contains(Area.HR);
		assertThat(Area.of("/hr/employees/42")).contains(Area.HR);
		assertThat(Area.of("/me/payslips")).contains(Area.SELF_SERVICE);
		assertThat(Area.of("/hrx")).isEmpty();
		assertThat(Area.of("/media")).isEmpty();
		assertThat(Area.of("/")).isEmpty();
		assertThat(Area.of("/notifications")).isEmpty();
	}

	@Test
	void adminOpensEveryOfficeAreaButNotTeamOrSelfService() {
		List<Role> admin = List.of(Role.ADMIN);
		assertThat(Area.ADMIN.allows(admin)).isTrue();
		assertThat(Area.HR.allows(admin)).isTrue();
		assertThat(Area.PAYROLL.allows(admin)).isTrue();
		assertThat(Area.FINANCE.allows(admin)).isTrue();
		assertThat(Area.TEAM.allows(admin)).isFalse();
		assertThat(Area.SELF_SERVICE.allows(admin)).isFalse();
	}

	@Test
	void eachRoleOpensOnlyItsOwnArea() {
		assertThat(Area.HR.allows(List.of(Role.PAYROLL, Role.EMPLOYEE))).isFalse();
		assertThat(Area.FINANCE.allows(List.of(Role.PAYROLL))).isFalse();
		assertThat(Area.TEAM.allows(List.of(Role.MANAGER))).isTrue();
		assertThat(Area.SELF_SERVICE.allows(List.of(Role.EMPLOYEE))).isTrue();
		assertThat(Area.ADMIN.allows(List.of(Role.HR, Role.PAYROLL, Role.FINANCE))).isFalse();
	}

	@Test
	void rolesMapToSpringSecurityAuthorities() {
		assertThat(Role.HR.authority()).isEqualTo("ROLE_HR");
		assertThat(Role.fromAuthority("ROLE_FINANCE")).contains(Role.FINANCE);
		assertThat(Role.fromAuthority("ROLE_USER")).isEmpty();
		assertThat(Role.fromAuthority("FINANCE")).isEmpty();
	}

}
