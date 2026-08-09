-- The no-show reason tag was previously written into stays.notes, overwriting any
-- operational note already recorded against the stay. Give it its own column so both
-- survive.
ALTER TABLE stays
    ADD COLUMN no_show_reason VARCHAR(100);
