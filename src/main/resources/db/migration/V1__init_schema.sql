-- 1. Companies (salons)
CREATE TABLE companies
(
    id                    BIGSERIAL PRIMARY KEY,
    name                  VARCHAR(255) NOT NULL,
    tax_id                VARCHAR(50),
    address               VARCHAR(255),
    opening_time          TIME         NOT NULL DEFAULT '09:00',
    closing_time          TIME         NOT NULL DEFAULT '17:00',
    slot_interval_minutes INT          NOT NULL DEFAULT 30,
    created_at            TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 2. Users
CREATE TABLE users
(
    id            BIGSERIAL PRIMARY KEY,
    phone_number  VARCHAR(20)  NOT NULL UNIQUE,
    email         VARCHAR(255) UNIQUE,
    password_hash VARCHAR(255),
    first_name    VARCHAR(100) NOT NULL,
    last_name     VARCHAR(100) NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 3. Company employees (roles)
CREATE TABLE company_employees
(
    id         BIGSERIAL PRIMARY KEY,
    company_id BIGINT      NOT NULL REFERENCES companies (id) ON DELETE CASCADE,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role       VARCHAR(50) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (company_id, user_id)
);

-- 4. Company customers (per-salon business data)
CREATE TABLE company_customers
(
    id         BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies (id) ON DELETE CASCADE,
    user_id    BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    notes      TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (company_id, user_id)
);

-- 5. Services (catalog)
CREATE TABLE services
(
    id               BIGSERIAL PRIMARY KEY,
    company_id       BIGINT       NOT NULL REFERENCES companies (id) ON DELETE CASCADE,
    name             VARCHAR(255) NOT NULL,
    duration_minutes INT          NOT NULL,
    price            INTEGER      NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 6. Reservations (price snapshot at booking time)
CREATE TABLE reservations
(
    id          BIGSERIAL PRIMARY KEY,
    company_id  BIGINT                   NOT NULL REFERENCES companies (id) ON DELETE CASCADE,
    customer_id BIGINT                   NOT NULL REFERENCES users (id),
    employee_id BIGINT                   NOT NULL REFERENCES users (id),
    service_id  BIGINT                   NOT NULL REFERENCES services (id),
    price       INTEGER                  NOT NULL,
    start_time  TIMESTAMP WITH TIME ZONE NOT NULL,
    end_time    TIMESTAMP WITH TIME ZONE NOT NULL,
    status      VARCHAR(50)              NOT NULL,
    version     BIGINT                   NOT NULL DEFAULT 0,
    created_at  TIMESTAMP WITH TIME ZONE          DEFAULT CURRENT_TIMESTAMP
);

-- Indexes
CREATE INDEX idx_reservations_company_id ON reservations (company_id);
CREATE INDEX idx_reservations_start_time ON reservations (start_time);
CREATE INDEX idx_reservations_employee_id ON reservations (employee_id);
CREATE INDEX idx_services_company_id ON services (company_id);
