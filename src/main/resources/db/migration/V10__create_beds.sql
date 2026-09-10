-- Named beds, phase 1: a bed is a first-class, tenant-scoped row under a room. Fully additive --
-- nothing else in the schema references this table yet, so it ships disconnected from stays.
CREATE TABLE beds (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    agency_id  UUID        NOT NULL REFERENCES agencies(id),
    room_id    UUID        NOT NULL REFERENCES rooms(id),
    label      VARCHAR(50) NOT NULL,
    status     VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_beds_room_label UNIQUE (room_id, label)
);

CREATE INDEX idx_beds_agency_id ON beds (agency_id);
CREATE INDEX idx_beds_room_id ON beds (room_id);
CREATE INDEX idx_beds_agency_room ON beds (agency_id, room_id);
