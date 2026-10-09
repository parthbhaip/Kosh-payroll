-- T10: the audit log. One row per recorded event, written in the same transaction as the change it describes and
-- never updated or deleted by the application. Actor and record are kept as text snapshots (no foreign keys), so an
-- event still reads correctly after the user or record it names changes. before_json/after_json hold only the changed
-- fields, with PAN and bank account numbers already masked; passwords and tokens are never recorded.
-- No CHECK on action: later tasks add actions without a migration.
CREATE TABLE audit_events (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    occurred_at DATETIME(6)   NOT NULL,
    actor_email VARCHAR(254),
    actor_name  VARCHAR(120)  NOT NULL,
    action      VARCHAR(40)   NOT NULL,
    entity_type VARCHAR(40),
    entity_id   BIGINT,
    summary     VARCHAR(300)  NOT NULL,
    before_json VARCHAR(4000),
    after_json  VARCHAR(4000),
    ip_address  VARCHAR(45),
    CONSTRAINT pk_audit_events PRIMARY KEY (id)
);

-- /admin/audit filters by date range, action, record and actor, newest first (by id).
CREATE INDEX idx_audit_events_occurred_at ON audit_events (occurred_at);
CREATE INDEX idx_audit_events_action ON audit_events (action);
CREATE INDEX idx_audit_events_entity ON audit_events (entity_type, entity_id);
CREATE INDEX idx_audit_events_actor_email ON audit_events (actor_email);
