-- Named beds, phase 4: stays.bed_id becomes mandatory now that StayService.resolveBed guarantees
-- a value on every write path (create/update/checkIn/move/bulkAssign). This completes the split
-- from V13, which only dropped rooms.capacity/blocked_spots -- see the named-beds plan's Critical
-- Implementation Details for why NOT NULL waited this long: enforcing it any earlier would have
-- failed every existing Stay-creating integration test the moment ./gradlew build ran them,
-- since nothing set bed_id until this phase.
ALTER TABLE stays ALTER COLUMN bed_id SET NOT NULL;
