CREATE TABLE properties (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    agency_id  UUID         NOT NULL REFERENCES agencies(id),
    name       VARCHAR(255) NOT NULL,
    address    TEXT,
    city       VARCHAR(100),
    status     VARCHAR(50)  NOT NULL DEFAULT 'ACTIVE',
    notes      TEXT,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_properties_agency_id ON properties (agency_id);
CREATE INDEX idx_properties_agency_status ON properties (agency_id, status);

CREATE TABLE rooms (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    agency_id     UUID         NOT NULL REFERENCES agencies(id),
    property_id   UUID         NOT NULL REFERENCES properties(id),
    name          VARCHAR(100) NOT NULL,
    floor         VARCHAR(50),
    capacity      INT          NOT NULL DEFAULT 1,
    blocked_spots INT          NOT NULL DEFAULT 0,
    gender_rule   VARCHAR(20)  NOT NULL DEFAULT 'ANY',
    status        VARCHAR(50)  NOT NULL DEFAULT 'ACTIVE',
    notes         TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_rooms_property_name UNIQUE (property_id, name),
    CONSTRAINT chk_rooms_capacity CHECK (capacity > 0),
    CONSTRAINT chk_rooms_blocked_spots CHECK (blocked_spots >= 0 AND blocked_spots <= capacity)
);

CREATE INDEX idx_rooms_agency_id ON rooms (agency_id);
CREATE INDEX idx_rooms_property_id ON rooms (property_id);
CREATE INDEX idx_rooms_agency_property ON rooms (agency_id, property_id);
