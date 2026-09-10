-- Named beds, phase 2: retire the capacity model beds replace, now that V12 has backfilled every
-- room and stay onto real beds. Deliberately does not touch stays.bed_id's nullability -- that
-- waits for V14 once StayService.resolveBed guarantees a value on every write (see the named-beds
-- plan's Critical Implementation Details). Same narrowing-migration rationale precedent as V9.
ALTER TABLE rooms
    DROP CONSTRAINT chk_rooms_capacity,
    DROP CONSTRAINT chk_rooms_blocked_spots,
    DROP COLUMN capacity,
    DROP COLUMN blocked_spots;
