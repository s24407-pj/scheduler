ALTER TABLE company_customer_blocks ADD COLUMN manual_blocked BOOLEAN NOT NULL DEFAULT FALSE,
 ADD COLUMN automatic_blocked BOOLEAN NOT NULL DEFAULT FALSE;
-- Legacy provenance cannot be established: retain existing blocks conservatively as manual.
UPDATE company_customer_blocks SET manual_blocked = blocked;
CREATE TABLE no_show_penalties (
 reservation_id BIGINT PRIMARY KEY REFERENCES reservations(id),
 company_id BIGINT NOT NULL,
 customer_id BIGINT NOT NULL,
 appointment_date DATE NOT NULL,
 active BOOLEAN NOT NULL DEFAULT TRUE,
 FOREIGN KEY(company_id, customer_id) REFERENCES company_customers(company_id,user_id)
);
INSERT INTO no_show_penalties(reservation_id,company_id,customer_id,appointment_date)
SELECT id,company_id,customer_id,(start_time AT TIME ZONE 'Europe/Warsaw')::date FROM reservations WHERE status='NO_SHOW';
CREATE INDEX current_no_show_penalties ON no_show_penalties(company_id,customer_id,appointment_date) WHERE active;
