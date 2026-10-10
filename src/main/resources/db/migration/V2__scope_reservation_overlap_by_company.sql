-- Reservation overlap is scoped to (company, employee): an employee's time at one company does not block another.
DO
$$
DECLARE
    constraint_name TEXT;
BEGIN
    FOR constraint_name IN
        SELECT conname
        FROM pg_constraint
        WHERE conrelid = 'reservations'::regclass
          AND contype = 'x'
    LOOP
        EXECUTE format('ALTER TABLE reservations DROP CONSTRAINT %I', constraint_name);
    END LOOP;
END
$$;

ALTER TABLE reservations
    ADD CONSTRAINT reservations_company_employee_no_overlap
        EXCLUDE USING gist (
        company_id WITH =,
        employee_id WITH =,
        tstzrange(start_time, end_time) WITH &&
        ) WHERE (status NOT IN ('CANCELLED', 'NO_SHOW'));

CREATE INDEX idx_reservations_company_employee_time ON reservations (company_id, employee_id, start_time);
