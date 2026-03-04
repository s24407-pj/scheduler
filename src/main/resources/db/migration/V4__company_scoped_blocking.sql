ALTER TABLE users DROP COLUMN no_show_count;
ALTER TABLE users DROP COLUMN blocked;

CREATE TABLE company_customer_blocks (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    customer_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    no_show_count INT NOT NULL DEFAULT 0,
    blocked BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (company_id, customer_id)
);
