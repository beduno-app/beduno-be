-- Aligns the rooms table with the room contract the frontend spec has carried since August and
-- the SPA has always sent. The backend had diverged on three points, and the divergence was only
-- visible as breakage: sorting by roomNumber came back 500, and genderRule arrived as a value the
-- client's type does not contain.
--
-- Safe as a narrowing conversion only because rooms is empty here: floor was VARCHAR(50) and
-- becomes INT, so any non-numeric floor would abort this migration rather than be silently
-- mangled. That is deliberate -- dropping "ground" or "M" quietly would be worse than a loud
-- failure -- but it does mean an environment holding such a value needs a decision before it can
-- migrate. Verified zero rows in production before deploying.
ALTER TABLE rooms RENAME COLUMN name TO room_number;
ALTER TABLE rooms RENAME CONSTRAINT uq_rooms_property_name TO uq_rooms_property_room_number;

ALTER TABLE rooms
    ALTER COLUMN floor TYPE INT USING NULLIF(btrim(floor), '')::INT;

-- ANY and MIXED name the same rule -- no gender restriction -- so this is a rename, not a
-- behaviour change. The client's union has always been MALE_ONLY | FEMALE_ONLY | MIXED, so ANY
-- arrived as an unhandled value on the screen that decides who may sleep where.
UPDATE rooms SET gender_rule = 'MIXED' WHERE gender_rule = 'ANY';
ALTER TABLE rooms ALTER COLUMN gender_rule SET DEFAULT 'MIXED';
