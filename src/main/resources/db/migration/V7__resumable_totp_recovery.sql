CREATE TABLE staff_recovery_authorizations (
    grant_hash varchar(64) PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES company_employees(id) ON DELETE CASCADE
);
CREATE INDEX staff_recovery_authorizations_account_idx ON staff_recovery_authorizations(account_id);
