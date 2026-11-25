-- Indexes for date-range reporting queries
CREATE INDEX idx_collection_entries_date ON collection_entries (entry_date);
CREATE INDEX idx_monthly_settlements_start_date ON monthly_settlements (start_date);
CREATE INDEX idx_monthly_settlements_end_date ON monthly_settlements (end_date);
