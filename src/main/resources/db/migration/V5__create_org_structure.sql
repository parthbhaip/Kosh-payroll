-- T12: the organisation structure HR maintains on /hr/org. Every table has a short unique code (stored upper-case) and
-- a unique name. An item in use is never deleted: later tables reference these through named foreign keys, which
-- refuse the delete, and HR deactivates the item instead (active = FALSE keeps it on existing records but out of the
-- pickers for new ones). Nothing is seeded here; T20 adds the demo company's structure.

CREATE TABLE departments (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    code         VARCHAR(10)  NOT NULL,
    name         VARCHAR(100) NOT NULL,
    active       BOOLEAN      NOT NULL,
    head_user_id BIGINT,
    created_at   DATETIME(6)  NOT NULL,
    updated_at   DATETIME(6)  NOT NULL,
    version      BIGINT       NOT NULL,
    CONSTRAINT pk_departments PRIMARY KEY (id),
    CONSTRAINT uk_departments_code UNIQUE (code),
    CONSTRAINT uk_departments_name UNIQUE (name),
    CONSTRAINT fk_departments_head_user FOREIGN KEY (head_user_id) REFERENCES users (id)
);

CREATE INDEX idx_departments_head_user ON departments (head_user_id);

CREATE TABLE designations (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    code       VARCHAR(10)  NOT NULL,
    name       VARCHAR(100) NOT NULL,
    active     BOOLEAN      NOT NULL,
    created_at DATETIME(6)  NOT NULL,
    updated_at DATETIME(6)  NOT NULL,
    version    BIGINT       NOT NULL,
    CONSTRAINT pk_designations PRIMARY KEY (id),
    CONSTRAINT uk_designations_code UNIQUE (code),
    CONSTRAINT uk_designations_name UNIQUE (name)
);

CREATE TABLE grades (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    code       VARCHAR(10)  NOT NULL,
    name       VARCHAR(100) NOT NULL,
    active     BOOLEAN      NOT NULL,
    created_at DATETIME(6)  NOT NULL,
    updated_at DATETIME(6)  NOT NULL,
    version    BIGINT       NOT NULL,
    CONSTRAINT pk_grades PRIMARY KEY (id),
    CONSTRAINT uk_grades_code UNIQUE (code),
    CONSTRAINT uk_grades_name UNIQUE (name)
);

CREATE TABLE cost_centres (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    code       VARCHAR(10)  NOT NULL,
    name       VARCHAR(100) NOT NULL,
    active     BOOLEAN      NOT NULL,
    created_at DATETIME(6)  NOT NULL,
    updated_at DATETIME(6)  NOT NULL,
    version    BIGINT       NOT NULL,
    CONSTRAINT pk_cost_centres PRIMARY KEY (id),
    CONSTRAINT uk_cost_centres_code UNIQUE (code),
    CONSTRAINT uk_cost_centres_name UNIQUE (name)
);

-- state_code: ISO 3166-2:IN code (com.kosh.common.IndianState), the key of the state-wise professional tax slabs.
-- pt_applicable: professional tax is deducted for employees working here.
CREATE TABLE work_locations (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    code          VARCHAR(10)  NOT NULL,
    name          VARCHAR(100) NOT NULL,
    active        BOOLEAN      NOT NULL,
    address_line1 VARCHAR(150) NOT NULL,
    address_line2 VARCHAR(150),
    city          VARCHAR(60)  NOT NULL,
    state_code    VARCHAR(2)   NOT NULL,
    pincode       VARCHAR(6)   NOT NULL,
    pt_applicable BOOLEAN      NOT NULL,
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    version       BIGINT       NOT NULL,
    CONSTRAINT pk_work_locations PRIMARY KEY (id),
    CONSTRAINT uk_work_locations_code UNIQUE (code),
    CONSTRAINT uk_work_locations_name UNIQUE (name)
);
