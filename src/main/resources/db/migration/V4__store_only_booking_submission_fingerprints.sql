-- Fingerprints and successful outcomes retain permanent replay/payload identity without retaining arbitrary bodies.
ALTER TABLE booking_submissions DROP COLUMN request_payload;
