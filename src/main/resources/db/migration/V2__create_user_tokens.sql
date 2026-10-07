-- T08: single-use links e-mailed to a user (set a password after an invite; T09 adds password resets).
-- Only a keyed hash (HmacHasher, 64 hex characters) of the token is stored, so a copy of the database cannot be used
-- to open a link. A link works while used_at is empty and expires_at is in the future; issuing a new one spends the
-- older ones of the same purpose.
CREATE TABLE user_tokens (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    user_id    BIGINT      NOT NULL,
    purpose    VARCHAR(20) NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    used_at    DATETIME(6),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version    BIGINT      NOT NULL,
    CONSTRAINT pk_user_tokens PRIMARY KEY (id),
    CONSTRAINT uk_user_tokens_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_user_tokens_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_user_tokens_purpose CHECK (purpose IN ('INVITE', 'RESET'))
);

-- Starts with user_id, so it also serves as the index for the foreign key.
CREATE INDEX idx_user_tokens_user_purpose ON user_tokens (user_id, purpose);

-- The user list (/admin/users) sorts by name and by last sign-in; e-mail already has its unique key.
CREATE INDEX idx_users_display_name ON users (display_name);
CREATE INDEX idx_users_last_login_at ON users (last_login_at);
