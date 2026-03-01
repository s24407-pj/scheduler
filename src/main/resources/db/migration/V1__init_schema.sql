-- 1. Companies (salons)
CREATE TABLE companies
(
    id                    BIGSERIAL PRIMARY KEY,
    name                  VARCHAR(255) NOT NULL,
    tax_id                VARCHAR(50),
    address               VARCHAR(255),
    opening_time          TIME         NOT NULL    DEFAULT '09:00',
    closing_time          TIME         NOT NULL    DEFAULT '17:00',
    slot_interval_minutes INT          NOT NULL    DEFAULT 30,
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

-- 5. Service categories
CREATE TABLE service_categories
(
    id         BIGSERIAL PRIMARY KEY,
    company_id BIGINT       NOT NULL REFERENCES companies (id) ON DELETE CASCADE,
    name       VARCHAR(100) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE (company_id, name)
);
CREATE INDEX idx_service_categories_company_id ON service_categories (company_id);

-- 6. Services (catalog)
CREATE TABLE services
(
    id               BIGSERIAL PRIMARY KEY,
    company_id       BIGINT       NOT NULL REFERENCES companies (id) ON DELETE CASCADE,
    name             VARCHAR(255) NOT NULL,
    duration_minutes INT          NOT NULL,
    price            INTEGER      NOT NULL,
    active           BOOLEAN      NOT NULL DEFAULT TRUE,
    category_id      BIGINT REFERENCES service_categories (id) ON DELETE SET NULL,
    created_at       TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_services_company_id ON services (company_id);

-- 7. Reservations (price snapshot at booking time)
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
CREATE INDEX idx_reservations_company_id ON reservations (company_id);
CREATE INDEX idx_reservations_start_time ON reservations (start_time);
CREATE INDEX idx_reservations_employee_id ON reservations (employee_id);

-- 8. Schedule blocks (employee unavailability / breaks)
CREATE TABLE schedule_blocks
(
    id          BIGSERIAL PRIMARY KEY,
    company_id  BIGINT                   NOT NULL REFERENCES companies (id) ON DELETE CASCADE,
    employee_id BIGINT                   NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    start_time  TIMESTAMP WITH TIME ZONE NOT NULL,
    end_time    TIMESTAMP WITH TIME ZONE NOT NULL,
    reason      VARCHAR(255),
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_schedule_blocks_employee_id ON schedule_blocks (employee_id);
CREATE INDEX idx_schedule_blocks_start_time  ON schedule_blocks (start_time);

-- 9. Employee weekly work schedules
CREATE TABLE employee_work_schedules
(
    id          BIGSERIAL PRIMARY KEY,
    company_id  BIGINT      NOT NULL REFERENCES companies (id) ON DELETE CASCADE,
    employee_id BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    day_of_week VARCHAR(10) NOT NULL,
    start_time  TIME        NOT NULL,
    end_time    TIME        NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE (company_id, employee_id, day_of_week),
    CHECK (end_time > start_time)
);
CREATE INDEX idx_employee_work_schedules_employee_id ON employee_work_schedules (employee_id);

-- 10. Employee service assignments
CREATE TABLE employee_services
(
    id          BIGSERIAL PRIMARY KEY,
    company_id  BIGINT NOT NULL REFERENCES companies (id) ON DELETE CASCADE,
    employee_id BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    service_id  BIGINT NOT NULL REFERENCES services (id) ON DELETE CASCADE,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE (employee_id, service_id)
);
CREATE INDEX idx_employee_services_employee_id ON employee_services (employee_id);
CREATE INDEX idx_employee_services_service_id  ON employee_services (service_id);

-- 11. Service images (up to 5 per service, stored in R2)
CREATE TABLE service_images
(
    id         BIGSERIAL PRIMARY KEY,
    service_id BIGINT       NOT NULL REFERENCES services (id) ON DELETE CASCADE,
    image_url  VARCHAR(500) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_service_images_service_id ON service_images (service_id);

ALTER TABLE reservations ADD COLUMN reminder_sent BOOLEAN NOT NULL DEFAULT FALSE;
