-- Named beds, phase 2: give stays a real (initially nullable) bed pointer. Nullable because no
-- write path resolves a bed until StayService.resolveBed lands in phase 4 -- V14 adds the NOT
-- NULL constraint once that guarantee actually holds. See the named-beds plan.
ALTER TABLE stays
    ADD COLUMN bed_id            UUID REFERENCES beds(id),
    ADD COLUMN bed_auto_assigned BOOLEAN NOT NULL DEFAULT true;

CREATE INDEX idx_stays_bed_occupancy ON stays (bed_id, status, date_from, date_to);
