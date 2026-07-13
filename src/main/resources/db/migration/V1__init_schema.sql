-- 1. Companies (salons)
CREATE TABLE companies
(
    id                           BIGSERIAL PRIMARY KEY,
    name                         VARCHAR(255) NOT NULL,
    tax_id                       VARCHAR(50),
    address                      VARCHAR(255),
    opening_time                 TIME         NOT NULL    DEFAULT '09:00',
    closing_time                 TIME         NOT NULL    DEFAULT '17:00',
    slot_interval_minutes        INT          NOT NULL    DEFAULT 30,
    max_no_shows                 INT          NOT NULL    DEFAULT 3,
    last_minute_discount_percent INT          NOT NULL    DEFAULT 0,
    last_minute_discount_hours   INT          NOT NULL    DEFAULT 24,
    min_booking_advance_minutes  INT          NOT NULL    DEFAULT 0,
    created_at                   TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
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
    photo_url     VARCHAR(500),
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

-- 5. Company-scoped customer block/no-show tracking
CREATE TABLE company_customer_blocks
(
    id            BIGSERIAL PRIMARY KEY,
    company_id    BIGINT  NOT NULL REFERENCES companies (id) ON DELETE CASCADE,
    customer_id   BIGINT  NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    no_show_count INT     NOT NULL DEFAULT 0,
    blocked       BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (company_id, customer_id)
);

-- 6. Offering categories
CREATE TABLE offering_categories
(
    id         BIGSERIAL PRIMARY KEY,
    company_id BIGINT                   NOT NULL REFERENCES companies (id) ON DELETE CASCADE,
    name       VARCHAR(100)             NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE (company_id, name)
);
CREATE INDEX idx_offering_categories_company_id ON offering_categories (company_id);

-- 7. Offerings (catalog)
CREATE TABLE offerings
(
    id               BIGSERIAL PRIMARY KEY,
    company_id       BIGINT       NOT NULL REFERENCES companies (id) ON DELETE CASCADE,
    name             VARCHAR(255) NOT NULL,
    duration_minutes INT          NOT NULL,
    price            INTEGER      NOT NULL,
    active           BOOLEAN      NOT NULL    DEFAULT TRUE,
    category_id      BIGINT       REFERENCES offering_categories (id) ON DELETE SET NULL,
    created_at       TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_offerings_company_id ON offerings (company_id);

-- 8. Reservations (price snapshot at booking time)
CREATE
EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE reservations
(
    id            BIGSERIAL PRIMARY KEY,
    company_id    BIGINT                   NOT NULL REFERENCES companies (id) ON DELETE CASCADE,
    customer_id   BIGINT                   NOT NULL REFERENCES users (id),
    employee_id   BIGINT                   NOT NULL REFERENCES users (id),
    service_id    BIGINT                   NOT NULL REFERENCES offerings (id),
    price         INTEGER                  NOT NULL,
    start_time    TIMESTAMP WITH TIME ZONE NOT NULL,
    end_time      TIMESTAMP WITH TIME ZONE NOT NULL,
    status        VARCHAR(50)              NOT NULL,
    version       BIGINT                   NOT NULL DEFAULT 0,
    reminder_sent BOOLEAN                  NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMP WITH TIME ZONE          DEFAULT CURRENT_TIMESTAMP,
    EXCLUDE USING gist (
        employee_id WITH =,
        tstzrange(start_time, end_time) WITH &&
    ) WHERE (status NOT IN ('CANCELLED', 'NO_SHOW'))
);
CREATE INDEX idx_reservations_company_id ON reservations (company_id);
CREATE INDEX idx_reservations_employee_time ON reservations (employee_id, start_time);

-- 9. Schedule blocks (employee unavailability / breaks)
CREATE TABLE schedule_blocks
(
    id          BIGSERIAL PRIMARY KEY,
    company_id  BIGINT                   NOT NULL,
    employee_id BIGINT                   NOT NULL,
    start_time  TIMESTAMP WITH TIME ZONE NOT NULL,
    end_time    TIMESTAMP WITH TIME ZONE NOT NULL,
    reason      VARCHAR(255),
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    FOREIGN KEY (company_id, employee_id)
        REFERENCES company_employees (company_id, user_id) ON DELETE CASCADE,
    EXCLUDE USING gist (
        company_id WITH =,
        employee_id WITH =,
        tstzrange(start_time, end_time) WITH &&
    )
);
CREATE INDEX idx_schedule_blocks_company_employee_time
    ON schedule_blocks (company_id, employee_id, start_time);

-- 10. Employee weekly work schedules
CREATE TABLE employee_work_schedules
(
    id          BIGSERIAL PRIMARY KEY,
    company_id  BIGINT                   NOT NULL REFERENCES companies (id) ON DELETE CASCADE,
    employee_id BIGINT                   NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    day_of_week VARCHAR(10)              NOT NULL,
    start_time  TIME                     NOT NULL,
    end_time    TIME                     NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE (company_id, employee_id, day_of_week),
    CHECK (end_time > start_time)
);
CREATE INDEX idx_employee_work_schedules_employee_id ON employee_work_schedules (employee_id);

-- 11. Employee offering assignments
CREATE TABLE employee_offerings
(
    id          BIGSERIAL PRIMARY KEY,
    company_id  BIGINT                   NOT NULL REFERENCES companies (id) ON DELETE CASCADE,
    employee_id BIGINT                   NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    offering_id BIGINT                   NOT NULL REFERENCES offerings (id) ON DELETE CASCADE,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE (employee_id, offering_id)
);
CREATE INDEX idx_employee_offerings_employee_id ON employee_offerings (employee_id);
CREATE INDEX idx_employee_offerings_offering_id ON employee_offerings (offering_id);

-- 12. Offering images (up to 5 per offering, stored in R2)
CREATE TABLE offering_images
(
    id          BIGSERIAL PRIMARY KEY,
    offering_id BIGINT                   NOT NULL REFERENCES offerings (id) ON DELETE CASCADE,
    image_url   VARCHAR(500)             NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_offering_images_offering_id ON offering_images (offering_id);
