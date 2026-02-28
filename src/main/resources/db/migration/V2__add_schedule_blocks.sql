CREATE TABLE schedule_blocks (
    id          BIGSERIAL PRIMARY KEY,
    company_id  BIGINT       NOT NULL REFERENCES companies (id) ON DELETE CASCADE,
    employee_id BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    start_time  TIMESTAMP WITH TIME ZONE NOT NULL,
    end_time    TIMESTAMP WITH TIME ZONE NOT NULL,
    reason      VARCHAR(255),
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_schedule_blocks_employee_id ON schedule_blocks (employee_id);
CREATE INDEX idx_schedule_blocks_start_time  ON schedule_blocks (start_time);
