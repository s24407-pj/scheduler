ALTER TABLE company_employees ADD COLUMN totp_secret text;
ALTER TABLE company_employees ADD COLUMN totp_last_step bigint;
ALTER TABLE company_employees ADD CONSTRAINT staff_totp_step_requires_secret
    CHECK (totp_last_step IS NULL OR totp_secret IS NOT NULL);
CREATE TABLE staff_recovery_codes (
    account_id uuid NOT NULL REFERENCES company_employees(id) ON DELETE CASCADE,
    code_hash varchar(64) NOT NULL,
    PRIMARY KEY (account_id, code_hash)
);
