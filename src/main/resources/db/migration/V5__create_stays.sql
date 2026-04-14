CREATE TABLE stays (
    id                   UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    agency_id            UUID        NOT NULL REFERENCES agencies(id),
    worker_id            UUID        NOT NULL REFERENCES workers(id),
    property_id          UUID        NOT NULL REFERENCES properties(id),
    room_id              UUID        NOT NULL REFERENCES rooms(id),
    date_from            DATE        NOT NULL,
    date_to              DATE,
    status               VARCHAR(50) NOT NULL DEFAULT 'PLANNED',
    override_reason      TEXT,
    confirmed_by_user_id UUID        REFERENCES users(id),
    notes                TEXT,
    version              BIGINT      NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_stays_dates CHECK (date_to IS NULL OR date_to > date_from)
);

CREATE INDEX idx_stays_agency_id ON stays (agency_id);
CREATE INDEX idx_stays_agency_worker ON stays (agency_id, worker_id);
CREATE INDEX idx_stays_agency_property ON stays (agency_id, property_id);
CREATE INDEX idx_stays_agency_status ON stays (agency_id, status);
-- Composite index for occupancy queries: filter by room + active statuses + date range
CREATE INDEX idx_stays_occupancy ON stays (room_id, status, date_from, date_to);
