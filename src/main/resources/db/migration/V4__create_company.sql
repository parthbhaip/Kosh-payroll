-- T11: the one company Kosh runs payroll for. A single row (id 1), inserted here with the pay-setting defaults from
-- PAYROLL-RULES.md and STATUTORY-RATES.md; the profile columns stay empty until an admin fills them in on
-- /admin/company. pan holds AES-GCM ciphertext (EncryptedStringConverter), never the PAN itself.
CREATE TABLE company (
    id                       BIGINT       NOT NULL AUTO_INCREMENT,
    legal_name               VARCHAR(150),
    brand_name               VARCHAR(80),
    address_line1            VARCHAR(150),
    address_line2            VARCHAR(150),
    city                     VARCHAR(60),
    state_code               VARCHAR(2),
    pincode                  VARCHAR(6),
    pan                      VARCHAR(255),
    tan                      VARCHAR(10),
    pf_establishment_code    VARCHAR(15),
    esi_employer_code        VARCHAR(17),
    pt_registration_number   VARCHAR(12),
    signatory_name           VARCHAR(120),
    signatory_designation    VARCHAR(80),
    logo_path                VARCHAR(100),
    proration_method         VARCHAR(20)  NOT NULL,
    pay_day                  INT          NOT NULL,
    attendance_cutoff_day    INT          NOT NULL,
    default_pf_policy        VARCHAR(10)  NOT NULL,
    gratuity_in_ctc          BOOLEAN      NOT NULL,
    pf_charges_in_ctc        BOOLEAN      NOT NULL,
    leave_encashment_divisor INT          NOT NULL,
    metro_cities             VARCHAR(500) NOT NULL,
    pf_due_day               INT          NOT NULL,
    esi_due_day              INT          NOT NULL,
    tds_due_day              INT          NOT NULL,
    pt_due_day               INT          NOT NULL,
    created_at               DATETIME(6)  NOT NULL,
    updated_at               DATETIME(6)  NOT NULL,
    version                  BIGINT       NOT NULL,
    CONSTRAINT pk_company PRIMARY KEY (id),
    CONSTRAINT ck_company_proration_method CHECK (proration_method IN ('CALENDAR_DAYS', 'FIXED_30', 'WORKING_DAYS')),
    CONSTRAINT ck_company_default_pf_policy CHECK (default_pf_policy IN ('RESTRICT', 'ACTUAL')),
    CONSTRAINT ck_company_pay_days CHECK (pay_day BETWEEN 2 AND 28 AND attendance_cutoff_day BETWEEN 1 AND 27
        AND attendance_cutoff_day < pay_day),
    CONSTRAINT ck_company_encashment_divisor CHECK (leave_encashment_divisor BETWEEN 20 AND 31),
    CONSTRAINT ck_company_due_days CHECK (pf_due_day BETWEEN 1 AND 31 AND esi_due_day BETWEEN 1 AND 31
        AND tds_due_day BETWEEN 1 AND 31 AND pt_due_day BETWEEN 1 AND 31)
);

-- Defaults: calendar-day proration, salary on the 7th and attendance closed by the 3rd of the following month, PF
-- restricted to the wage ceiling, no gratuity provision in CTC, PF admin and EDLI in CTC, encashment divisor 30, the
-- eight 50% HRA cities of the Income-tax Rules, 2026 (STATUTORY-RATES.md), PF and ESI due on the 15th, TDS on the 7th
-- and PT on the last day (31 = last day of the month).
INSERT INTO company (id, proration_method, pay_day, attendance_cutoff_day, default_pf_policy, gratuity_in_ctc,
    pf_charges_in_ctc, leave_encashment_divisor, metro_cities, pf_due_day, esi_due_day, tds_due_day, pt_due_day,
    created_at, updated_at, version)
VALUES (1, 'CALENDAR_DAYS', 7, 3, 'RESTRICT', FALSE, TRUE, 30,
    'Delhi, Mumbai, Kolkata, Chennai, Bengaluru, Hyderabad, Pune, Ahmedabad', 15, 15, 7, 31,
    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0);
