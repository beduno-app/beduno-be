-- Database-level guard against double-booking a bed or a worker.
--
-- Every conflict check in the application is a COUNT(*) under READ COMMITTED followed by an
-- INSERT, with nothing between them. Two planners submitting at the same moment both see the bed
-- free and both succeed; @Version on stays protects updates to an existing row and does nothing
-- for this. The exception constraints close the window in the only place where it can be closed.
--
-- Semantics deliberately mirror the application's: stays are half-open periods [date_from,
-- date_to), an open-ended stay runs to infinity, and only the three statuses that actually reserve
-- a bed participate. A CHECKED_OUT, CANCELLED or NO_SHOW stay places no claim on anything.

CREATE EXTENSION IF NOT EXISTS btree_gist;

-- Pre-flight: refuse to install the guard while data already violates it, rather than failing
-- with a constraint error that says nothing about which rows are at fault. Anything found here
-- predates the guard and needs resolving by hand; GET /properties/{id}/exceptions now reports
-- the same conflicts as BED_CONFLICT.
DO $$
DECLARE
    conflicting_beds INT;
    conflicting_workers INT;
BEGIN
    SELECT COUNT(*) INTO conflicting_beds
    FROM stays a
    JOIN stays b
      ON a.bed_id = b.bed_id
     AND a.id < b.id
     AND a.status IN ('PLANNED', 'EXPECTED_TODAY', 'CHECKED_IN')
     AND b.status IN ('PLANNED', 'EXPECTED_TODAY', 'CHECKED_IN')
     AND daterange(a.date_from, a.date_to, '[)') && daterange(b.date_from, b.date_to, '[)');

    SELECT COUNT(*) INTO conflicting_workers
    FROM stays a
    JOIN stays b
      ON a.worker_id = b.worker_id
     AND a.id < b.id
     AND a.status IN ('PLANNED', 'EXPECTED_TODAY', 'CHECKED_IN')
     AND b.status IN ('PLANNED', 'EXPECTED_TODAY', 'CHECKED_IN')
     AND daterange(a.date_from, a.date_to, '[)') && daterange(b.date_from, b.date_to, '[)');

    IF conflicting_beds > 0 OR conflicting_workers > 0 THEN
        RAISE EXCEPTION
            'Cannot add stay overlap constraints: % overlapping bed pair(s) and % overlapping worker pair(s) already exist. Resolve them first (see GET /properties/{id}/exceptions).',
            conflicting_beds, conflicting_workers;
    END IF;
END $$;

-- daterange(date_from, NULL) is already unbounded above, so open-ended stays need no sentinel.
ALTER TABLE stays
    ADD CONSTRAINT excl_stays_bed_period
    EXCLUDE USING gist (
        bed_id WITH =,
        daterange(date_from, date_to, '[)') WITH &&
    )
    WHERE (status IN ('PLANNED', 'EXPECTED_TODAY', 'CHECKED_IN'));

ALTER TABLE stays
    ADD CONSTRAINT excl_stays_worker_period
    EXCLUDE USING gist (
        worker_id WITH =,
        daterange(date_from, date_to, '[)') WITH &&
    )
    WHERE (status IN ('PLANNED', 'EXPECTED_TODAY', 'CHECKED_IN'));
