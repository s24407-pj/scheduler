CREATE TABLE booking_quotes (
 id UUID PRIMARY KEY,
 customer_id BIGINT NOT NULL REFERENCES customer_accounts(id),
 company_id BIGINT NOT NULL REFERENCES companies(id),
 service_id BIGINT NOT NULL REFERENCES offerings(id),
 start_time TIMESTAMPTZ NOT NULL,
 accepted_service_name VARCHAR(255) NOT NULL,
 price INT NOT NULL CHECK(price >= 0),
 duration_minutes INT NOT NULL CHECK(duration_minutes > 0),
 expires_at TIMESTAMPTZ NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
