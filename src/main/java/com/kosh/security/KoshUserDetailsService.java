package com.kosh.security;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kosh.user.UserRepository;

/**
 * Loads sign-in accounts from the users table by e-mail in any case. An invited user who has not set a password yet
 * counts as unknown, so the sign-in form answers exactly as for a wrong e-mail.
 */
@Service
public class KoshUserDetailsService implements UserDetailsService {

	private final UserRepository users;

	private final Clock clock;

	public KoshUserDetailsService(UserRepository users, Clock clock) {
		this.users = users;
		this.clock = clock;
	}

	@Override
	@Transactional(readOnly = true)
	public KoshUserDetails loadUserByUsername(String email) {
		return users.findByEmailIgnoreCase(email)
			.filter(user -> user.getPasswordHash() != null)
			.map(user -> new KoshUserDetails(user, user.isLockedAt(LocalDateTime.now(clock))))
			.orElseThrow(() -> new UsernameNotFoundException("No account with a password for this e-mail"));
	}

}
