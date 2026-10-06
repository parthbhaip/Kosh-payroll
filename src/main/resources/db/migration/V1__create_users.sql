-- T06: sign-in accounts and the roles each one holds (one user, many roles).
-- E-mails are stored trimmed and lower-case, so the unique key is case-insensitive on H2 as well as MySQL.
-- ck_users_email_lower only bites on H2: MySQL's default ci collation compares case-insensitively, so there the
-- CHECK always passes and User.normalizeEmail is the only guard. Seeds and native SQL must insert lower-case e-mails.
-- password_hash is empty for an invited user who has not set a password yet; such an account cannot sign in.
-- The optional link to an employee is added together with the employees table in T15.
CREATE TABLE users (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    email              VARCHAR(254) NOT NULL,
    password_hash      VARCHAR(100),
    display_name       VARCHAR(120) NOT NULL,
    enabled            BOOLEAN      NOT NULL DEFAULT TRUE,
    failed_login_count INT          NOT NULL DEFAULT 0,
    locked_until       DATETIME(6),
    last_login_at      DATETIME(6),
    created_at         DATETIME(6)  NOT NULL,
    updated_at         DATETIME(6)  NOT NULL,
    version            BIGINT       NOT NULL,
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uk_users_email UNIQUE (email),
    CONSTRAINT ck_users_email_lower CHECK (email = LOWER(email)),
    CONSTRAINT ck_users_failed_login_count CHECK (failed_login_count >= 0)
);

-- The primary key starts with user_id, so it also serves as the index for the foreign key.
CREATE TABLE user_roles (
    user_id BIGINT      NOT NULL,
    role    VARCHAR(20) NOT NULL,
    CONSTRAINT pk_user_roles PRIMARY KEY (user_id, role),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_user_roles_role CHECK (role IN ('ADMIN', 'HR', 'PAYROLL', 'FINANCE', 'MANAGER', 'EMPLOYEE'))
);

-- The user list filters by role (T08).
CREATE INDEX idx_user_roles_role ON user_roles (role);
