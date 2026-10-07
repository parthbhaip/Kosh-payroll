package com.kosh.user;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kosh.common.BusinessRuleException;
import com.kosh.common.HmacHasher;
import com.kosh.user.UserToken.Purpose;

/**
 * Single-use password links: 32 random bytes, base64url in the e-mail, only their keyed hash in the database. A link
 * works once, until it expires ({@link Purpose#validity()}), and only while the account is enabled. Issuing a new link
 * spends the user's older ones of the same purpose. Open to anonymous visitors on purpose: the token is the credential.
 */
@Service
public class PasswordLinkService {

	public static final int MIN_PASSWORD_LENGTH = 8;

	public static final int MAX_PASSWORD_LENGTH = 64;

	/** BCrypt reads only the first 72 bytes, and Spring's encoder refuses anything longer. */
	static final int MAX_PASSWORD_BYTES = 72;

	public static final String LINK_NOT_VALID = "This link has expired or has already been used.";

	private static final int TOKEN_BYTES = 32;

	/** Longer input is never a token we issued (43 characters); skip the hashing. */
	private static final int MAX_TOKEN_LENGTH = 100;

	/** A new link: the token for the e-mail (never stored or logged) and when it stops working. */
	public record Link(String token, LocalDateTime expiresAt) {

	}

	private final UserTokenRepository tokens;

	private final HmacHasher hasher;

	private final PasswordEncoder passwordEncoder;

	private final Clock clock;

	private final SecureRandom random = new SecureRandom();

	public PasswordLinkService(UserTokenRepository tokens, HmacHasher hasher, PasswordEncoder passwordEncoder,
			Clock clock) {
		this.tokens = tokens;
		this.hasher = hasher;
		this.passwordEncoder = passwordEncoder;
		this.clock = clock;
	}

	/** Issues a new link for the user and spends their older unused links of the same purpose. */
	@Transactional
	public Link issue(User user, Purpose purpose) {
		LocalDateTime now = LocalDateTime.now(clock);
		spendOpenLinks(user, purpose, now);
		byte[] bytes = new byte[TOKEN_BYTES];
		random.nextBytes(bytes);
		String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		LocalDateTime expiresAt = now.plus(purpose.validity());
		tokens.save(new UserToken(user, purpose, hasher.hash(token), expiresAt));
		return new Link(token, expiresAt);
	}

	/** The user a link still works for, or empty for an unknown, used, expired or disabled one. */
	@Transactional(readOnly = true)
	public Optional<User> userFor(String token, Purpose purpose) {
		return usable(token, purpose).map(UserToken::getUser);
	}

	/**
	 * Sets the user's password from a link and spends it.
	 * @throws BusinessRuleException on field {@code password} for a password outside the rules, or form-wide
	 * ({@link #LINK_NOT_VALID}) when the link no longer works
	 */
	@Transactional
	public User setPassword(String token, Purpose purpose, String password) {
		UserToken link = usable(token, purpose).orElseThrow(() -> new BusinessRuleException(null, LINK_NOT_VALID));
		checkPassword(password);
		User user = link.getUser();
		user.setPasswordHash(passwordEncoder.encode(password));
		spendOpenLinks(user, purpose, LocalDateTime.now(clock));
		return user;
	}

	/**
	 * 8 to 64 characters and at most 72 bytes in UTF-8; no composition rules, so a short sentence works.
	 * @throws BusinessRuleException on field {@code password} with the reason
	 */
	public static void checkPassword(String password) {
		if (password == null || password.isEmpty()) {
			throw new BusinessRuleException("password", "Enter a password.");
		}
		int length = password.codePointCount(0, password.length());
		if (length < MIN_PASSWORD_LENGTH || length > MAX_PASSWORD_LENGTH) {
			throw new BusinessRuleException("password",
					"Use %d to %d characters.".formatted(MIN_PASSWORD_LENGTH, MAX_PASSWORD_LENGTH));
		}
		if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
			throw new BusinessRuleException("password", "That password is too long for these characters. Use a shorter one.");
		}
	}

	private Optional<UserToken> usable(String token, Purpose purpose) {
		if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
			return Optional.empty();
		}
		LocalDateTime now = LocalDateTime.now(clock);
		return tokens.findByTokenHashAndPurpose(hasher.hash(token), purpose)
			.filter(link -> link.isUsableAt(now) && link.getUser().isEnabled())
			// An invite is for a first password only.
			.filter(link -> purpose != Purpose.INVITE || link.getUser().getPasswordHash() == null);
	}

	private void spendOpenLinks(User user, Purpose purpose, LocalDateTime now) {
		tokens.findByUserAndPurposeAndUsedAtIsNull(user, purpose).forEach(link -> link.use(now));
	}

}
