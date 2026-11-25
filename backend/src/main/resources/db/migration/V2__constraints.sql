-- Enforce one collection entry per supplier per date
DELETE FROM collection_entries
WHERE id NOT IN (
    SELECT MAX(id) FROM collection_entries GROUP BY supplier_id, entry_date
);

ALTER TABLE collection_entries
    ADD CONSTRAINT uq_collection_supplier_date UNIQUE (supplier_id, entry_date);
