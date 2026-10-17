-- T14: statutory rules as effective-dated versions (PLAN T14, STATUTORY-RATES.md). Each version is one row of
-- statutory_rules (family, scope, effective_from, effective_to, source note, last verified) and one row of its family's
-- table with the same id (JPA joined inheritance), plus slab rows for professional tax and income tax.
-- A family has one version in force on any date: a new version starts on a later date and the previous one is closed
-- the day before (effective_to). Values are never edited; only effective_to and verified_on change.
-- scope: 'IN' for rules that apply across India, the ISO 3166-2:IN state code for state rules (professional tax).
-- Rates are percentages (DECIMAL(7,4): 8.3300 means 8.33%); amounts are rupees (DECIMAL(12,2)).
-- Statutory due days are company pay settings (V4), not rules here.
CREATE TABLE statutory_rules (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    family         VARCHAR(20)   NOT NULL,
    scope          VARCHAR(2)    NOT NULL,
    effective_from DATE          NOT NULL,
    effective_to   DATE,
    source_note    VARCHAR(1000) NOT NULL,
    verified_on    DATE          NOT NULL,
    created_at     DATETIME(6)   NOT NULL,
    updated_at     DATETIME(6)   NOT NULL,
    version        BIGINT        NOT NULL,
    CONSTRAINT pk_statutory_rules PRIMARY KEY (id),
    CONSTRAINT uk_statutory_rules_start UNIQUE (family, scope, effective_from),
    CONSTRAINT ck_statutory_rules_family CHECK (family IN ('WAGES', 'PF', 'ESI', 'PT', 'NEW_TAX', 'OLD_TAX',
        'OLD_TAX_CAPS', 'GRATUITY')),
    CONSTRAINT ck_statutory_rules_scope CHECK ((family = 'PT' AND scope <> 'IN') OR (family <> 'PT' AND scope = 'IN')),
    CONSTRAINT ck_statutory_rules_dates CHECK (effective_to IS NULL OR effective_to >= effective_from)
);
-- "In force on a date" looks at both dates.
CREATE INDEX idx_statutory_rules_dates ON statutory_rules (effective_from, effective_to);

-- Wages under the Code on Wages: excluded pay above excluded_cap_rate % of total pay is added back to wages; full and
-- final wages are due within settlement_due_working_days working days of the last working day.
CREATE TABLE wage_rules (
    id                          BIGINT       NOT NULL,
    excluded_cap_rate           DECIMAL(7,4) NOT NULL,
    settlement_due_working_days INT          NOT NULL,
    CONSTRAINT pk_wage_rules PRIMARY KEY (id),
    CONSTRAINT fk_wage_rules_version FOREIGN KEY (id) REFERENCES statutory_rules (id)
);

CREATE TABLE pf_rules (
    id              BIGINT        NOT NULL,
    wage_ceiling    DECIMAL(12,2) NOT NULL,
    employee_rate   DECIMAL(7,4)  NOT NULL,
    employer_rate   DECIMAL(7,4)  NOT NULL,
    eps_rate        DECIMAL(7,4)  NOT NULL,
    eps_age_limit   INT           NOT NULL,
    edli_rate       DECIMAL(7,4)  NOT NULL,
    admin_rate      DECIMAL(7,4)  NOT NULL,
    admin_minimum   DECIMAL(12,2) NOT NULL,
    edli_admin_rate DECIMAL(7,4)  NOT NULL,
    CONSTRAINT pk_pf_rules PRIMARY KEY (id),
    CONSTRAINT fk_pf_rules_version FOREIGN KEY (id) REFERENCES statutory_rules (id)
);

-- period_start_month: the first month (1-6) of the two six-month contribution periods (4: April and October).
CREATE TABLE esi_rules (
    id                      BIGINT        NOT NULL,
    wage_ceiling            DECIMAL(12,2) NOT NULL,
    disability_wage_ceiling DECIMAL(12,2) NOT NULL,
    employee_rate           DECIMAL(7,4)  NOT NULL,
    employer_rate           DECIMAL(7,4)  NOT NULL,
    exempt_daily_wage       DECIMAL(12,2) NOT NULL,
    period_start_month      INT           NOT NULL,
    CONSTRAINT pk_esi_rules PRIMARY KEY (id),
    CONSTRAINT fk_esi_rules_version FOREIGN KEY (id) REFERENCES statutory_rules (id),
    CONSTRAINT ck_esi_rules_period CHECK (period_start_month BETWEEN 1 AND 6)
);

CREATE TABLE pt_rules (
    id         BIGINT        NOT NULL,
    annual_cap DECIMAL(12,2) NOT NULL,
    CONSTRAINT pk_pt_rules PRIMARY KEY (id),
    CONSTRAINT fk_pt_rules_version FOREIGN KEY (id) REFERENCES statutory_rules (id)
);

-- A slab applies to gross monthly salary up to salary_up_to (NULL: no upper limit, the top slab) and above the
-- previous slab of the same gender. february_amount replaces monthly_amount in February.
CREATE TABLE pt_slabs (
    pt_rule_id      BIGINT        NOT NULL,
    line_no         INT           NOT NULL,
    gender          VARCHAR(6)    NOT NULL,
    salary_up_to    DECIMAL(12,2),
    monthly_amount  DECIMAL(12,2) NOT NULL,
    february_amount DECIMAL(12,2) NOT NULL,
    CONSTRAINT pk_pt_slabs PRIMARY KEY (pt_rule_id, line_no),
    CONSTRAINT fk_pt_slabs_rule FOREIGN KEY (pt_rule_id) REFERENCES pt_rules (id),
    CONSTRAINT ck_pt_slabs_gender CHECK (gender IN ('MALE', 'FEMALE'))
);

-- One row per regime version (family NEW_TAX or OLD_TAX). employer_nps_rate: employer NPS deductible up to this % of
-- Basic + DA; NULL where the regime allows no such deduction in Kosh (old regime).
CREATE TABLE tax_regime_rules (
    id                  BIGINT        NOT NULL,
    standard_deduction  DECIMAL(12,2) NOT NULL,
    rebate_income_limit DECIMAL(12,2) NOT NULL,
    rebate_max          DECIMAL(12,2) NOT NULL,
    cess_rate           DECIMAL(7,4)  NOT NULL,
    employer_nps_rate   DECIMAL(7,4),
    CONSTRAINT pk_tax_regime_rules PRIMARY KEY (id),
    CONSTRAINT fk_tax_regime_rules_version FOREIGN KEY (id) REFERENCES statutory_rules (id)
);

-- income_up_to NULL: the top slab.
CREATE TABLE tax_slabs (
    tax_rule_id  BIGINT        NOT NULL,
    line_no      INT           NOT NULL,
    income_up_to DECIMAL(12,2),
    rate         DECIMAL(7,4)  NOT NULL,
    CONSTRAINT pk_tax_slabs PRIMARY KEY (tax_rule_id, line_no),
    CONSTRAINT fk_tax_slabs_rule FOREIGN KEY (tax_rule_id) REFERENCES tax_regime_rules (id)
);

-- The rate applies once total income is above income_above (the highest threshold crossed wins).
CREATE TABLE tax_surcharges (
    tax_rule_id  BIGINT        NOT NULL,
    line_no      INT           NOT NULL,
    income_above DECIMAL(12,2) NOT NULL,
    rate         DECIMAL(7,4)  NOT NULL,
    CONSTRAINT pk_tax_surcharges PRIMARY KEY (tax_rule_id, line_no),
    CONSTRAINT fk_tax_surcharges_rule FOREIGN KEY (tax_rule_id) REFERENCES tax_regime_rules (id)
);

-- Old-regime deduction caps, by what the deduction is for (section numbers changed under the 2025 Act).
CREATE TABLE tax_deduction_caps (
    id                 BIGINT        NOT NULL,
    investments        DECIMAL(12,2) NOT NULL,
    health_insurance   DECIMAL(12,2) NOT NULL,
    home_loan_interest DECIMAL(12,2) NOT NULL,
    CONSTRAINT pk_tax_deduction_caps PRIMARY KEY (id),
    CONSTRAINT fk_tax_deduction_caps_version FOREIGN KEY (id) REFERENCES statutory_rules (id)
);

-- Gratuity = wages x days_per_year / month_days x years; a final part-year of more than part_year_months months
-- counts as a year; at most max_amount.
CREATE TABLE gratuity_rules (
    id                           BIGINT        NOT NULL,
    eligibility_years            INT           NOT NULL,
    fixed_term_eligibility_years INT           NOT NULL,
    days_per_year                INT           NOT NULL,
    month_days                   INT           NOT NULL,
    part_year_months             INT           NOT NULL,
    max_amount                   DECIMAL(12,2) NOT NULL,
    CONSTRAINT pk_gratuity_rules PRIMARY KEY (id),
    CONSTRAINT fk_gratuity_rules_version FOREIGN KEY (id) REFERENCES statutory_rules (id)
);

-- Seed: exactly the values of docs/STATUTORY-RATES.md (researched and verified 5 October 2026), in force from
-- 1 April 2026, the start of tax year 2026-27.
INSERT INTO statutory_rules (family, scope, effective_from, effective_to, source_note, verified_on, created_at,
    updated_at, version)
VALUES
    ('WAGES', 'IN', '2026-04-01', NULL,
     'Code on Wages, 2019, in force with the four labour codes from 21 Nov 2025: wages are Basic + DA + retaining allowance, and excluded pay (HRA, conveyance, special allowance, bonus, overtime, commission, employer PF and similar) above 50% of total remuneration is added back. Full and final wages are due within 2 working days of the last working day. https://www.india-briefing.com/news/salary-structure-india-labor-code-compliance-guide-44332.html and https://www.pockethrms.com/blog/full-and-final-settlement/',
     '2026-10-05', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
    ('PF', 'IN', '2026-04-01', NULL,
     'Wage ceiling of 15,000 rupees a month, unchanged since Sep 2014 and re-notified under the Code on Social Security on 29 May 2026: https://www.indianpaycalculator.in/govt-news/epf-wage-ceiling-stays-15000-ss-code-2026 . EPF admin charges 0.5% of EPF wages (at least 500 rupees a month per establishment) and EDLI admin charges waived from 1 Apr 2017: https://caclub.in/pf-admin-charges-reduced-and-edli-admin-charges-waived-by-epfo',
     '2026-10-05', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
    ('ESI', 'IN', '2026-04-01', NULL,
     'Covered when gross wages are at most 21,000 rupees a month (25,000 for persons with disability). Employee 0.75%, employer 3.25%; no employee share when the average daily wage is at most 176 rupees. Contribution periods 1 Apr to 30 Sep and 1 Oct to 31 Mar. No ceiling revision notified as of June 2026: https://bhattandjoshiassociates.com/esic-wage-ceiling-change-what-it-would-mean-for-employers/',
     '2026-10-05', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
    ('PT', 'MH', '2026-04-01', NULL,
     'Maharashtra professional tax on salaries, by gross salary of the month: men nil up to 7,500 rupees, 175 up to 10,000 and 200 above (300 in February); women nil up to 25,000 and 200 above (300 in February); at most 2,500 rupees a year. https://cleartax.in/s/professional-tax-maharashtra',
     '2026-10-05', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
    ('NEW_TAX', 'IN', '2026-04-01', NULL,
     'Income-tax Act, 2025, tax year 2026-27; Budget 2026 changed no slab, deduction, rebate, cess or surcharge. Standard deduction 75,000; rebate up to 60,000 for total income up to 12 lakh, with marginal relief; surcharge 10%, 15% and 25% above 50 lakh, 1 crore and 2 crore; cess 4%; employer NPS deductible up to 14% of Basic + DA. https://bhattandjoshiassociates.com/indian-union-budget-2026-27-key-income-tax-changes-and-their-impact-on-salaried-individuals/ and https://taxguru.in/?p=1024891',
     '2026-10-05', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
    ('OLD_TAX', 'IN', '2026-04-01', NULL,
     'Income-tax Act, 2025, tax year 2026-27, old regime for individuals below 60 years: standard deduction 50,000; rebate up to 12,500 for total income up to 5 lakh; surcharge 10%, 15%, 25% and 37% above 50 lakh, 1 crore, 2 crore and 5 crore; cess 4%. https://www.taxheal.com/income-tax-slab-for-fy-2026-27.html',
     '2026-10-05', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
    ('OLD_TAX_CAPS', 'IN', '2026-04-01', NULL,
     'Seed values carried over from the Income-tax Act, 1961: investments 1,50,000 (old 80C), health insurance for self and family under 60 25,000 (old 80D), interest on a home loan for a self-occupied house 2,00,000. Section numbers changed under the 2025 Act; verify these caps before a demo. HRA exemption and professional tax have no cap here.',
     '2026-10-05', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
    ('GRATUITY', 'IN', '2026-04-01', NULL,
     'Code on Social Security, 2020: eligible after 5 years of continuous service, or 1 year for fixed-term employees; last drawn wages x 15/26 x completed years, where a final part-year of more than 6 months counts as a full year; at most 20 lakh rupees. https://www.pockethrms.com/blog/full-and-final-settlement/',
     '2026-10-05', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0);

INSERT INTO wage_rules (id, excluded_cap_rate, settlement_due_working_days)
SELECT id, 50.0000, 2 FROM statutory_rules WHERE family = 'WAGES';

INSERT INTO pf_rules (id, wage_ceiling, employee_rate, employer_rate, eps_rate, eps_age_limit, edli_rate, admin_rate,
    admin_minimum, edli_admin_rate)
SELECT id, 15000.00, 12.0000, 12.0000, 8.3300, 58, 0.5000, 0.5000, 500.00, 0.0000
FROM statutory_rules WHERE family = 'PF';

INSERT INTO esi_rules (id, wage_ceiling, disability_wage_ceiling, employee_rate, employer_rate, exempt_daily_wage,
    period_start_month)
SELECT id, 21000.00, 25000.00, 0.7500, 3.2500, 176.00, 4 FROM statutory_rules WHERE family = 'ESI';

INSERT INTO pt_rules (id, annual_cap) SELECT id, 2500.00 FROM statutory_rules WHERE family = 'PT' AND scope = 'MH';
INSERT INTO pt_slabs (pt_rule_id, line_no, gender, salary_up_to, monthly_amount, february_amount)
SELECT id, 0, 'MALE', 7500.00, 0.00, 0.00 FROM statutory_rules WHERE family = 'PT' AND scope = 'MH';
INSERT INTO pt_slabs (pt_rule_id, line_no, gender, salary_up_to, monthly_amount, february_amount)
SELECT id, 1, 'MALE', 10000.00, 175.00, 175.00 FROM statutory_rules WHERE family = 'PT' AND scope = 'MH';
INSERT INTO pt_slabs (pt_rule_id, line_no, gender, salary_up_to, monthly_amount, february_amount)
SELECT id, 2, 'MALE', NULL, 200.00, 300.00 FROM statutory_rules WHERE family = 'PT' AND scope = 'MH';
INSERT INTO pt_slabs (pt_rule_id, line_no, gender, salary_up_to, monthly_amount, february_amount)
SELECT id, 3, 'FEMALE', 25000.00, 0.00, 0.00 FROM statutory_rules WHERE family = 'PT' AND scope = 'MH';
INSERT INTO pt_slabs (pt_rule_id, line_no, gender, salary_up_to, monthly_amount, february_amount)
SELECT id, 4, 'FEMALE', NULL, 200.00, 300.00 FROM statutory_rules WHERE family = 'PT' AND scope = 'MH';

INSERT INTO tax_regime_rules (id, standard_deduction, rebate_income_limit, rebate_max, cess_rate, employer_nps_rate)
SELECT id, 75000.00, 1200000.00, 60000.00, 4.0000, 14.0000 FROM statutory_rules WHERE family = 'NEW_TAX';
INSERT INTO tax_regime_rules (id, standard_deduction, rebate_income_limit, rebate_max, cess_rate, employer_nps_rate)
SELECT id, 50000.00, 500000.00, 12500.00, 4.0000, NULL FROM statutory_rules WHERE family = 'OLD_TAX';

INSERT INTO tax_slabs (tax_rule_id, line_no, income_up_to, rate)
SELECT id, 0, 400000.00, 0.0000 FROM statutory_rules WHERE family = 'NEW_TAX';
INSERT INTO tax_slabs (tax_rule_id, line_no, income_up_to, rate)
SELECT id, 1, 800000.00, 5.0000 FROM statutory_rules WHERE family = 'NEW_TAX';
INSERT INTO tax_slabs (tax_rule_id, line_no, income_up_to, rate)
SELECT id, 2, 1200000.00, 10.0000 FROM statutory_rules WHERE family = 'NEW_TAX';
INSERT INTO tax_slabs (tax_rule_id, line_no, income_up_to, rate)
SELECT id, 3, 1600000.00, 15.0000 FROM statutory_rules WHERE family = 'NEW_TAX';
INSERT INTO tax_slabs (tax_rule_id, line_no, income_up_to, rate)
SELECT id, 4, 2000000.00, 20.0000 FROM statutory_rules WHERE family = 'NEW_TAX';
INSERT INTO tax_slabs (tax_rule_id, line_no, income_up_to, rate)
SELECT id, 5, 2400000.00, 25.0000 FROM statutory_rules WHERE family = 'NEW_TAX';
INSERT INTO tax_slabs (tax_rule_id, line_no, income_up_to, rate)
SELECT id, 6, NULL, 30.0000 FROM statutory_rules WHERE family = 'NEW_TAX';
INSERT INTO tax_slabs (tax_rule_id, line_no, income_up_to, rate)
SELECT id, 0, 250000.00, 0.0000 FROM statutory_rules WHERE family = 'OLD_TAX';
INSERT INTO tax_slabs (tax_rule_id, line_no, income_up_to, rate)
SELECT id, 1, 500000.00, 5.0000 FROM statutory_rules WHERE family = 'OLD_TAX';
INSERT INTO tax_slabs (tax_rule_id, line_no, income_up_to, rate)
SELECT id, 2, 1000000.00, 20.0000 FROM statutory_rules WHERE family = 'OLD_TAX';
INSERT INTO tax_slabs (tax_rule_id, line_no, income_up_to, rate)
SELECT id, 3, NULL, 30.0000 FROM statutory_rules WHERE family = 'OLD_TAX';

INSERT INTO tax_surcharges (tax_rule_id, line_no, income_above, rate)
SELECT id, 0, 5000000.00, 10.0000 FROM statutory_rules WHERE family = 'NEW_TAX';
INSERT INTO tax_surcharges (tax_rule_id, line_no, income_above, rate)
SELECT id, 1, 10000000.00, 15.0000 FROM statutory_rules WHERE family = 'NEW_TAX';
INSERT INTO tax_surcharges (tax_rule_id, line_no, income_above, rate)
SELECT id, 2, 20000000.00, 25.0000 FROM statutory_rules WHERE family = 'NEW_TAX';
INSERT INTO tax_surcharges (tax_rule_id, line_no, income_above, rate)
SELECT id, 0, 5000000.00, 10.0000 FROM statutory_rules WHERE family = 'OLD_TAX';
INSERT INTO tax_surcharges (tax_rule_id, line_no, income_above, rate)
SELECT id, 1, 10000000.00, 15.0000 FROM statutory_rules WHERE family = 'OLD_TAX';
INSERT INTO tax_surcharges (tax_rule_id, line_no, income_above, rate)
SELECT id, 2, 20000000.00, 25.0000 FROM statutory_rules WHERE family = 'OLD_TAX';
INSERT INTO tax_surcharges (tax_rule_id, line_no, income_above, rate)
SELECT id, 3, 50000000.00, 37.0000 FROM statutory_rules WHERE family = 'OLD_TAX';

INSERT INTO tax_deduction_caps (id, investments, health_insurance, home_loan_interest)
SELECT id, 150000.00, 25000.00, 200000.00 FROM statutory_rules WHERE family = 'OLD_TAX_CAPS';

INSERT INTO gratuity_rules (id, eligibility_years, fixed_term_eligibility_years, days_per_year, month_days,
    part_year_months, max_amount)
SELECT id, 5, 1, 15, 26, 6, 2000000.00 FROM statutory_rules WHERE family = 'GRATUITY';
