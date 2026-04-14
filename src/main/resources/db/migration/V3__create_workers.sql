CREATE TABLE workers (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    agency_id   UUID         NOT NULL REFERENCES agencies(id),
    internal_id VARCHAR(100) NOT NULL,
    first_name  VARCHAR(100) NOT NULL,
    last_name   VARCHAR(100) NOT NULL,
    gender      VARCHAR(10)  NOT NULL,
    nationality VARCHAR(100),
    phone       VARCHAR(50),
    email       VARCHAR(255),
    date_of_birth DATE,
    tags        TEXT[]       NOT NULL DEFAULT '{}',
    notes       TEXT,
    status      VARCHAR(50)  NOT NULL DEFAULT 'ACTIVE',
    deleted_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_workers_agency_internal_id UNIQUE (agency_id, internal_id)
);

CREATE INDEX idx_workers_agency_id ON workers (agency_id);
CREATE INDEX idx_workers_agency_status ON workers (agency_id, status);
CREATE INDEX idx_workers_agency_gender ON workers (agency_id, gender);
