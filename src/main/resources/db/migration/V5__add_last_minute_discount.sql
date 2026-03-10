ALTER TABLE companies ADD COLUMN last_minute_discount_percent INT NOT NULL DEFAULT 0;
ALTER TABLE companies ADD COLUMN last_minute_discount_hours INT NOT NULL DEFAULT 24;
