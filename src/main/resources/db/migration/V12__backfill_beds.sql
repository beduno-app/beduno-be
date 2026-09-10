-- Named beds, phase 2: backfill every pre-existing room and stay onto the bed model rather than
-- resetting them (the confirmed decision -- other sessions, notably the frontend, are actively
-- exercising this data). Two steps: generate a bed inventory sized to each room's current capacity
-- for any room that doesn't already have beds (from phase 1's optional API), then re-point every
-- existing stay at a bed in its current room by rank, wrapping around with modulo if a room has
-- more stay rows than beds -- safe because historical/non-overlapping stays don't need to satisfy
-- the live occupancy constraint the way a fresh assignment does.
INSERT INTO beds (id, agency_id, room_id, label, status, created_at, updated_at)
SELECT gen_random_uuid(), r.agency_id, r.id, gs::text, 'ACTIVE', now(), now()
FROM rooms r, generate_series(1, r.capacity) gs
WHERE NOT EXISTS (SELECT 1 FROM beds b WHERE b.room_id = r.id);

WITH ranked_stays AS (
    SELECT id, room_id, row_number() OVER (PARTITION BY room_id ORDER BY created_at, id) - 1 AS rn
    FROM stays
), room_bed_counts AS (
    SELECT room_id, count(*) AS bed_count FROM beds GROUP BY room_id
), ranked_beds AS (
    SELECT id, room_id, row_number() OVER (PARTITION BY room_id ORDER BY label) - 1 AS rn
    FROM beds
)
UPDATE stays s SET bed_id = rb.id
FROM ranked_stays rs
JOIN room_bed_counts c ON c.room_id = rs.room_id
JOIN ranked_beds rb ON rb.room_id = rs.room_id AND rb.rn = rs.rn % c.bed_count
WHERE s.id = rs.id;
