ALTER TABLE company_employees ADD COLUMN auth_version BIGINT NOT NULL DEFAULT 0;
CREATE TABLE staff_password_links (
    staff_account_id BIGINT PRIMARY KEY REFERENCES company_employees(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL
);
