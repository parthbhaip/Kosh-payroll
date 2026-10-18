-- T15: employees. Everyone who works for the company has a row here, including the people who run Kosh.
-- code: KSH-0001 and up, handed out by employee_code_counter below and never reused or changed.
-- Names: first name required; middle and last name optional (some people have one name).
-- gender: male, female or transgender, as EPFO and ESIC record it (professional tax slabs: com.kosh.common.Gender).
-- Addresses, emergency contact and bank details are optional groups: either every required field of the group is
-- filled in or none is (com.kosh.employee.Address, EmergencyContact, BankAccount).
-- pan and bank_account_number are AES-GCM ciphertext (EncryptedStringConverter); pan_hash is the keyed HMAC of the
-- upper-case PAN (HmacHasher), so the unique key catches the same PAN twice without decrypting anything.
-- user_id: the sign-in account of this employee, if they have one (one account per employee).
-- reporting_manager_id: the employee this one reports to; a manager's team is everyone pointing at them.
-- Foreign keys to the organisation structure are plain (no cascade), so a department, designation, grade, cost centre
-- or work location in use is deactivated, not deleted (see V5).
CREATE TABLE employees (
    id                         BIGINT       NOT NULL AUTO_INCREMENT,
    code                       VARCHAR(12)  NOT NULL,
    first_name                 VARCHAR(60)  NOT NULL,
    middle_name                VARCHAR(60),
    last_name                  VARCHAR(60),
    gender                     VARCHAR(11)  NOT NULL,
    date_of_birth              DATE         NOT NULL,
    personal_email             VARCHAR(254),
    mobile                     VARCHAR(10)  NOT NULL,
    current_address_line1      VARCHAR(150),
    current_address_line2      VARCHAR(150),
    current_city               VARCHAR(60),
    current_state_code         VARCHAR(2),
    current_pincode            VARCHAR(6),
    permanent_address_line1    VARCHAR(150),
    permanent_address_line2    VARCHAR(150),
    permanent_city             VARCHAR(60),
    permanent_state_code       VARCHAR(2),
    permanent_pincode          VARCHAR(6),
    emergency_contact_name     VARCHAR(120),
    emergency_contact_relation VARCHAR(40),
    emergency_contact_phone    VARCHAR(10),
    joining_date               DATE         NOT NULL,
    confirmation_date          DATE,
    employment_type            VARCHAR(10)  NOT NULL,
    contract_end_date          DATE,
    status                     VARCHAR(10)  NOT NULL,
    department_id              BIGINT       NOT NULL,
    designation_id             BIGINT       NOT NULL,
    grade_id                   BIGINT,
    location_id                BIGINT       NOT NULL,
    cost_centre_id             BIGINT,
    reporting_manager_id       BIGINT,
    user_id                    BIGINT,
    pan                        VARCHAR(255),
    pan_hash                   VARCHAR(64),
    uan                        VARCHAR(12),
    esi_ip_number              VARCHAR(10),
    pf_policy                  VARCHAR(10)  NOT NULL,
    pt_exempt                  BOOLEAN      NOT NULL,
    pt_exempt_reason           VARCHAR(200),
    person_with_disability     BOOLEAN      NOT NULL,
    bank_name                  VARCHAR(100),
    bank_ifsc                  VARCHAR(11),
    bank_account_number        VARCHAR(255),
    bank_account_holder        VARCHAR(120),
    created_at                 DATETIME(6)  NOT NULL,
    updated_at                 DATETIME(6)  NOT NULL,
    version                    BIGINT       NOT NULL,
    CONSTRAINT pk_employees PRIMARY KEY (id),
    CONSTRAINT uk_employees_code UNIQUE (code),
    CONSTRAINT uk_employees_pan_hash UNIQUE (pan_hash),
    CONSTRAINT uk_employees_uan UNIQUE (uan),
    CONSTRAINT uk_employees_esi_ip_number UNIQUE (esi_ip_number),
    CONSTRAINT uk_employees_user UNIQUE (user_id),
    CONSTRAINT fk_employees_department FOREIGN KEY (department_id) REFERENCES departments (id),
    CONSTRAINT fk_employees_designation FOREIGN KEY (designation_id) REFERENCES designations (id),
    CONSTRAINT fk_employees_grade FOREIGN KEY (grade_id) REFERENCES grades (id),
    CONSTRAINT fk_employees_location FOREIGN KEY (location_id) REFERENCES work_locations (id),
    CONSTRAINT fk_employees_cost_centre FOREIGN KEY (cost_centre_id) REFERENCES cost_centres (id),
    CONSTRAINT fk_employees_reporting_manager FOREIGN KEY (reporting_manager_id) REFERENCES employees (id),
    CONSTRAINT fk_employees_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_employees_gender CHECK (gender IN ('MALE', 'FEMALE', 'TRANSGENDER')),
    CONSTRAINT ck_employees_employment_type CHECK (employment_type IN ('PERMANENT', 'PROBATION', 'FIXED_TERM', 'INTERN')),
    CONSTRAINT ck_employees_status CHECK (status IN ('ACTIVE', 'ON_NOTICE', 'EXITED')),
    CONSTRAINT ck_employees_pf_policy CHECK (pf_policy IN ('RESTRICT', 'ACTUAL', 'EXCLUDED')),
    CONSTRAINT ck_employees_contract_end CHECK (employment_type <> 'FIXED_TERM' OR contract_end_date IS NOT NULL),
    CONSTRAINT ck_employees_pt_exempt_reason CHECK (pt_exempt = TRUE OR pt_exempt_reason IS NULL),
    CONSTRAINT ck_employees_pan_pair CHECK ((pan IS NULL AND pan_hash IS NULL) OR (pan IS NOT NULL AND pan_hash IS NOT NULL)),
    CONSTRAINT ck_employees_bank CHECK ((bank_name IS NULL AND bank_ifsc IS NULL AND bank_account_number IS NULL
        AND bank_account_holder IS NULL) OR (bank_name IS NOT NULL AND bank_ifsc IS NOT NULL
        AND bank_account_number IS NOT NULL AND bank_account_holder IS NOT NULL))
);

-- Foreign keys (user_id is covered by its unique key).
CREATE INDEX idx_employees_department ON employees (department_id);
CREATE INDEX idx_employees_designation ON employees (designation_id);
CREATE INDEX idx_employees_grade ON employees (grade_id);
CREATE INDEX idx_employees_location ON employees (location_id);
CREATE INDEX idx_employees_cost_centre ON employees (cost_centre_id);
CREATE INDEX idx_employees_reporting_manager ON employees (reporting_manager_id);
-- The employee directory (T16) filters by status and employment type and sorts by name.
CREATE INDEX idx_employees_status ON employees (status);
CREATE INDEX idx_employees_employment_type ON employees (employment_type);
CREATE INDEX idx_employees_name ON employees (first_name, last_name);

-- One row: the number in the last employee code handed out (KSH-0001 is 1). EmployeeRepository.nextCode moves it on
-- with an UPDATE in the transaction that adds the employee; the row lock makes concurrent adds take turns, and an add
-- that rolls back gives its number back.
CREATE TABLE employee_code_counter (
    id          INT NOT NULL,
    last_number INT NOT NULL,
    CONSTRAINT pk_employee_code_counter PRIMARY KEY (id),
    CONSTRAINT ck_employee_code_counter_id CHECK (id = 1)
);

INSERT INTO employee_code_counter (id, last_number) VALUES (1, 0);
