ALTER TABLE entries
    ADD CONSTRAINT chk_entries_order_index_non_negative
        CHECK (order_index IS NULL OR order_index >= 0),
    ADD CONSTRAINT chk_entries_skip_count_non_negative
        CHECK (skip_count >= 0);
