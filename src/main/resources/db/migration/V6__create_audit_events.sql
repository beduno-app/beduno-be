CREATE TABLE audit_events (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    agency_id      UUID        NOT NULL REFERENCES agencies(id),
    entity_type    VARCHAR(50) NOT NULL,
    entity_id      UUID        NOT NULL,
    action         VARCHAR(50) NOT NULL,
    actor_user_id  UUID,
    previous_state JSONB,
    new_state      JSONB,
    reason         TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_events_agency_id ON audit_events (agency_id);
CREATE INDEX idx_audit_events_entity ON audit_events (agency_id, entity_type, entity_id);
CREATE INDEX idx_audit_events_actor ON audit_events (agency_id, actor_user_id);
CREATE INDEX idx_audit_events_created_at ON audit_events (agency_id, created_at DESC);
