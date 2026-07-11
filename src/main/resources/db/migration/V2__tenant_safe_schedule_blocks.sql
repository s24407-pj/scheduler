DELETE FROM schedule_blocks b
WHERE NOT EXISTS (
    SELECT 1
    FROM company_employees ce
    WHERE ce.company_id = b.company_id
      AND ce.user_id = b.employee_id
);

ALTER TABLE schedule_blocks
    ADD CONSTRAINT fk_schedule_blocks_company_employee
        FOREIGN KEY (company_id, employee_id)
        REFERENCES company_employees (company_id, user_id)
        ON DELETE CASCADE;

ALTER TABLE schedule_blocks
    DROP CONSTRAINT schedule_blocks_employee_id_tstzrange_excl;

ALTER TABLE schedule_blocks
    ADD CONSTRAINT schedule_blocks_company_employee_time_excl
        EXCLUDE USING gist (
            company_id WITH =,
            employee_id WITH =,
            tstzrange(start_time, end_time) WITH &&
        );

DROP INDEX idx_schedule_blocks_employee_id;

CREATE INDEX idx_schedule_blocks_company_employee_time
    ON schedule_blocks (company_id, employee_id, start_time);
