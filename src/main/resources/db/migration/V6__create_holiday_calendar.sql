-- T13: holiday calendar and work week, per work location (HR edits them on /hr/holidays).
-- Plain foreign keys to work_locations (no cascade): a location with holidays or a work week is "in use" and is
-- deactivated, not deleted (see V5).

-- holiday_type: NATIONAL and FESTIVAL close the location; OPTIONAL holidays are working days that employees may take
-- off as leave. date_to_check: copied from the previous year and not yet confirmed by HR (festival dates move).
CREATE TABLE holidays (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    location_id   BIGINT       NOT NULL,
    holiday_date  DATE         NOT NULL,
    name          VARCHAR(100) NOT NULL,
    holiday_type  VARCHAR(10)  NOT NULL,
    date_to_check BOOLEAN      NOT NULL,
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    version       BIGINT       NOT NULL,
    CONSTRAINT pk_holidays PRIMARY KEY (id),
    -- One holiday a day per location; its index also serves the foreign key and the year look-ups.
    CONSTRAINT uk_holidays_location_date UNIQUE (location_id, holiday_date),
    CONSTRAINT fk_holidays_location FOREIGN KEY (location_id) REFERENCES work_locations (id),
    CONSTRAINT ck_holidays_type CHECK (holiday_type IN ('NATIONAL', 'FESTIVAL', 'OPTIONAL'))
);

-- weekly_offs: the days off each week, e.g. 'SATURDAY:2,4 SUNDAY' (Sunday every week, the 2nd and 4th Saturday of
-- the month). A location without a row has Sunday off (com.kosh.company.WeeklyOffs).
CREATE TABLE work_weeks (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    location_id BIGINT       NOT NULL,
    weekly_offs VARCHAR(150) NOT NULL,
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    version     BIGINT       NOT NULL,
    CONSTRAINT pk_work_weeks PRIMARY KEY (id),
    CONSTRAINT uk_work_weeks_location UNIQUE (location_id),
    CONSTRAINT fk_work_weeks_location FOREIGN KEY (location_id) REFERENCES work_locations (id)
);
