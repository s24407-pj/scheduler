-- Delivery state is authoritative in notification_deliveries. Retain historical audit JSON unchanged.
ALTER TABLE reservations DROP COLUMN reminder_sent;
ALTER TABLE reservations DROP COLUMN service_name_source;

-- Preserve existing provenance, including historical LEGACY rows; new writes must choose their source explicitly.
ALTER TABLE reservations ALTER COLUMN creation_source DROP DEFAULT;
