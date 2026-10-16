-- Consolidated pre-production schema, authorized 2026-10-15.
-- Fresh databases only: existing developer databases must be recreated before applying this new Flyway baseline.
-- Customer and staff accounts are independent realms; appointment history is never deleted by the API.
SET search_path TO public;

CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public;

COMMENT ON EXTENSION btree_gist IS 'support for indexing common datatypes in GiST';

CREATE FUNCTION public.audit_reservation_change() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE action_name TEXT;
BEGIN
    IF TG_OP = 'UPDATE' AND to_jsonb(OLD) = to_jsonb(NEW) THEN RETURN NEW; END IF;
    action_name := CASE
        WHEN TG_OP = 'INSERT' THEN 'CREATE'
        WHEN OLD.archived IS DISTINCT FROM NEW.archived THEN CASE WHEN NEW.archived THEN 'ARCHIVE' ELSE 'UNARCHIVE' END
        WHEN OLD.status IS DISTINCT FROM NEW.status THEN CASE WHEN NEW.status = 'BOOKED' THEN 'RESTORE' ELSE 'STATUS_CHANGE' END
        ELSE 'EDIT' END;
    INSERT INTO reservation_audit(reservation_id, actor_type, actor_account, occurred_at, action, before_state, after_state)
    VALUES (NEW.id, coalesce(nullif(current_setting('scheduler.actor_type', true), ''), 'SYSTEM'),
        nullif(current_setting('scheduler.actor_account', true), ''),
        coalesce(nullif(current_setting('scheduler.audit_time', true), '')::timestamptz, CURRENT_TIMESTAMP),
        action_name, CASE WHEN TG_OP = 'UPDATE' THEN to_jsonb(OLD) ELSE NULL END, to_jsonb(NEW));
    RETURN NEW;
END $$;

CREATE TABLE public.booking_quotes (
    id uuid NOT NULL,
    customer_id bigint NOT NULL,
    company_id bigint NOT NULL,
    service_id bigint NOT NULL,
    start_time timestamp with time zone NOT NULL,
    accepted_service_name character varying(255) NOT NULL,
    price integer NOT NULL,
    duration_minutes integer NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT booking_quotes_duration_minutes_check CHECK ((duration_minutes > 0)),
    CONSTRAINT booking_quotes_price_check CHECK ((price >= 0))
);

CREATE TABLE public.booking_submissions (
    account_key character varying(100) NOT NULL,
    submission_key character varying(200) NOT NULL,
    request_hash character varying(64) NOT NULL,
    request_payload text NOT NULL,
    reservation_id bigint,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE TABLE public.companies (
    id bigint NOT NULL,
    name character varying(255) NOT NULL,
    tax_id character varying(50),
    address character varying(255),
    opening_time time without time zone DEFAULT '09:00:00'::time without time zone NOT NULL,
    closing_time time without time zone DEFAULT '17:00:00'::time without time zone NOT NULL,
    slot_interval_minutes integer DEFAULT 30 NOT NULL,
    max_no_shows integer DEFAULT 3 NOT NULL,
    last_minute_discount_percent integer DEFAULT 0 NOT NULL,
    last_minute_discount_hours integer DEFAULT 24 NOT NULL,
    min_booking_advance_minutes integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    max_booking_advance_days integer DEFAULT 90 NOT NULL,
    max_future_customer_bookings integer DEFAULT 10 NOT NULL,
    CONSTRAINT companies_max_booking_advance_days_check CHECK ((max_booking_advance_days >= 0)),
    CONSTRAINT companies_max_future_customer_bookings_check CHECK ((max_future_customer_bookings >= 0))
);

CREATE SEQUENCE public.companies_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.companies_id_seq OWNED BY public.companies.id;

CREATE TABLE public.company_customer_blocks (
    id bigint NOT NULL,
    company_id bigint NOT NULL,
    customer_id bigint NOT NULL,
    no_show_count integer DEFAULT 0 NOT NULL,
    blocked boolean DEFAULT false NOT NULL,
    manual_blocked boolean DEFAULT false NOT NULL,
    automatic_blocked boolean DEFAULT false NOT NULL
);

CREATE SEQUENCE public.company_customer_blocks_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.company_customer_blocks_id_seq OWNED BY public.company_customer_blocks.id;

CREATE TABLE public.company_customers (
    id bigint NOT NULL,
    company_id bigint NOT NULL,
    user_id bigint NOT NULL,
    notes text,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    first_name character varying(100) NOT NULL,
    last_name character varying(100) NOT NULL,
    contact_email character varying(255)
);

CREATE SEQUENCE public.company_customers_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.company_customers_id_seq OWNED BY public.company_customers.id;

CREATE TABLE public.company_employees (
    id bigint NOT NULL,
    company_id bigint NOT NULL,
    user_id bigint NOT NULL,
    role character varying(50) NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    login_email character varying(255),
    password_hash character varying(255),
    first_name character varying(100) DEFAULT ''::character varying NOT NULL,
    last_name character varying(100) DEFAULT ''::character varying NOT NULL,
    contact_phone character varying(20),
    photo_url character varying(500),
    enabled boolean DEFAULT true NOT NULL,
    eligibility_mode character varying(30) DEFAULT 'ALL_SERVICES'::character varying NOT NULL,
    auth_version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT employee_eligibility_mode CHECK (((eligibility_mode)::text = ANY ((ARRAY['ALL_SERVICES'::character varying, 'SPECIFIC_SERVICES'::character varying])::text[]))),
    CONSTRAINT staff_normalized_login CHECK (((login_email IS NULL) OR ((login_email)::text = lower(TRIM(BOTH FROM login_email)))))
);

CREATE SEQUENCE public.company_employees_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.company_employees_id_seq OWNED BY public.company_employees.id;

CREATE TABLE public.customer_accounts (
    id bigint NOT NULL,
    phone_number character varying(20) NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);

ALTER TABLE public.customer_accounts ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.customer_accounts_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.employee_offerings (
    id bigint NOT NULL,
    company_id bigint NOT NULL,
    employee_id bigint NOT NULL,
    offering_id bigint NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE SEQUENCE public.employee_offerings_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.employee_offerings_id_seq OWNED BY public.employee_offerings.id;

CREATE TABLE public.employee_schedule_overrides (
    id bigint NOT NULL,
    company_id bigint NOT NULL,
    employee_id bigint NOT NULL,
    schedule_date date NOT NULL,
    day_off boolean NOT NULL,
    start_time time without time zone,
    end_time time without time zone,
    CONSTRAINT employee_schedule_overrides_check CHECK (((day_off AND (start_time IS NULL) AND (end_time IS NULL)) OR ((NOT day_off) AND (start_time IS NOT NULL) AND (end_time IS NOT NULL) AND (end_time > start_time))))
);

ALTER TABLE public.employee_schedule_overrides ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.employee_schedule_overrides_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.employee_work_schedules (
    id bigint NOT NULL,
    company_id bigint NOT NULL,
    employee_id bigint NOT NULL,
    day_of_week character varying(10) NOT NULL,
    start_time time without time zone NOT NULL,
    end_time time without time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT employee_work_schedules_check CHECK ((end_time > start_time))
);

CREATE SEQUENCE public.employee_work_schedules_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.employee_work_schedules_id_seq OWNED BY public.employee_work_schedules.id;

CREATE TABLE public.no_show_penalties (
    reservation_id bigint NOT NULL,
    company_id bigint NOT NULL,
    customer_id bigint NOT NULL,
    appointment_date date NOT NULL,
    active boolean DEFAULT true NOT NULL
);

CREATE TABLE public.notification_deliveries (
    id bigint NOT NULL,
    kind character varying(30) NOT NULL,
    reservation_id bigint,
    company_id bigint NOT NULL,
    customer_id bigint NOT NULL,
    reservation_revision bigint,
    appointment_date date,
    state character varying(30) DEFAULT 'PENDING'::character varying NOT NULL,
    attempts integer DEFAULT 0 NOT NULL,
    next_attempt_at timestamp with time zone NOT NULL,
    deadline timestamp with time zone NOT NULL,
    run_token uuid,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT notification_deliveries_attempts_check CHECK ((attempts >= 0)),
    CONSTRAINT notification_deliveries_check CHECK (((((kind)::text = 'BLOCK_SUMMARY'::text) AND (reservation_id IS NULL)) OR (((kind)::text <> 'BLOCK_SUMMARY'::text) AND (reservation_id IS NOT NULL)))),
    CONSTRAINT notification_deliveries_kind_check CHECK (((kind)::text = ANY ((ARRAY['CREATE'::character varying, 'EDIT'::character varying, 'RESTORE'::character varying, 'CANCEL'::character varying, 'BLOCK_SUMMARY'::character varying, 'REMINDER'::character varying])::text[]))),
    CONSTRAINT notification_deliveries_state_check CHECK (((state)::text = ANY ((ARRAY['PENDING'::character varying, 'SENDING'::character varying, 'ACCEPTED'::character varying, 'TEMPORARY_FAILURE'::character varying, 'AMBIGUOUS'::character varying, 'PAUSED'::character varying, 'DISCARDED'::character varying])::text[])))
);

ALTER TABLE public.notification_deliveries ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.notification_deliveries_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.notification_delivery_reservations (
    delivery_id bigint NOT NULL,
    reservation_id bigint NOT NULL
);

CREATE TABLE public.offering_categories (
    id bigint NOT NULL,
    company_id bigint NOT NULL,
    name character varying(100) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE SEQUENCE public.offering_categories_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.offering_categories_id_seq OWNED BY public.offering_categories.id;

CREATE TABLE public.offering_images (
    id bigint NOT NULL,
    offering_id bigint NOT NULL,
    image_url character varying(500) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE SEQUENCE public.offering_images_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.offering_images_id_seq OWNED BY public.offering_images.id;

CREATE TABLE public.offerings (
    id bigint NOT NULL,
    company_id bigint NOT NULL,
    name character varying(255) NOT NULL,
    duration_minutes integer NOT NULL,
    price integer NOT NULL,
    active boolean DEFAULT true NOT NULL,
    category_id bigint,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP
);

CREATE SEQUENCE public.offerings_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.offerings_id_seq OWNED BY public.offerings.id;

CREATE TABLE public.reservation_audit (
    id bigint NOT NULL,
    reservation_id bigint NOT NULL,
    actor_type character varying(20) NOT NULL,
    actor_account character varying(100),
    occurred_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    action character varying(40) NOT NULL,
    before_state jsonb,
    after_state jsonb NOT NULL
);

ALTER TABLE public.reservation_audit ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.reservation_audit_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.reservation_commands (
    reservation_id bigint NOT NULL,
    account_key character varying(100) NOT NULL,
    command_key character varying(200) NOT NULL,
    request_hash character varying(64) NOT NULL,
    outcome_revision bigint NOT NULL,
    outcome_status character varying(20) NOT NULL
);

CREATE TABLE public.reservations (
    id bigint NOT NULL,
    company_id bigint NOT NULL,
    customer_id bigint NOT NULL,
    employee_id bigint NOT NULL,
    service_id bigint NOT NULL,
    price integer NOT NULL,
    start_time timestamp with time zone NOT NULL,
    end_time timestamp with time zone NOT NULL,
    status character varying(50) NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    reminder_sent boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    accepted_service_name character varying(255) NOT NULL,
    service_name_source character varying(40) DEFAULT 'ACCEPTED'::character varying NOT NULL,
    agreed_duration_minutes integer NOT NULL,
    creation_source character varying(20) DEFAULT 'LEGACY'::character varying NOT NULL,
    archived boolean DEFAULT false NOT NULL,
    internal_cancellation_reason text,
    outcome_explanation text,
    customer_explanation text,
    CONSTRAINT reservation_duration CHECK ((agreed_duration_minutes > 0)),
    CONSTRAINT reservation_positive_interval CHECK ((end_time > start_time)),
    CONSTRAINT reservation_price CHECK ((price >= 0)),
    CONSTRAINT reservation_status CHECK (((status)::text = ANY ((ARRAY['BOOKED'::character varying, 'CANCELLED'::character varying, 'COMPLETED'::character varying, 'NO_SHOW'::character varying])::text[])))
);

CREATE SEQUENCE public.reservations_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.reservations_id_seq OWNED BY public.reservations.id;

CREATE TABLE public.schedule_blocks (
    id bigint NOT NULL,
    company_id bigint NOT NULL,
    employee_id bigint NOT NULL,
    start_time timestamp with time zone NOT NULL,
    end_time timestamp with time zone NOT NULL,
    reason character varying(255),
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE SEQUENCE public.schedule_blocks_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.schedule_blocks_id_seq OWNED BY public.schedule_blocks.id;

CREATE TABLE public.staff_password_links (
    staff_account_id bigint NOT NULL,
    token_hash character varying(64) NOT NULL,
    expires_at timestamp with time zone NOT NULL
);

CREATE SEQUENCE public.staff_public_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER TABLE ONLY public.companies ALTER COLUMN id SET DEFAULT nextval('public.companies_id_seq'::regclass);

ALTER TABLE ONLY public.company_customer_blocks ALTER COLUMN id SET DEFAULT nextval('public.company_customer_blocks_id_seq'::regclass);

ALTER TABLE ONLY public.company_customers ALTER COLUMN id SET DEFAULT nextval('public.company_customers_id_seq'::regclass);

ALTER TABLE ONLY public.company_employees ALTER COLUMN id SET DEFAULT nextval('public.company_employees_id_seq'::regclass);

ALTER TABLE ONLY public.employee_offerings ALTER COLUMN id SET DEFAULT nextval('public.employee_offerings_id_seq'::regclass);

ALTER TABLE ONLY public.employee_work_schedules ALTER COLUMN id SET DEFAULT nextval('public.employee_work_schedules_id_seq'::regclass);

ALTER TABLE ONLY public.offering_categories ALTER COLUMN id SET DEFAULT nextval('public.offering_categories_id_seq'::regclass);

ALTER TABLE ONLY public.offering_images ALTER COLUMN id SET DEFAULT nextval('public.offering_images_id_seq'::regclass);

ALTER TABLE ONLY public.offerings ALTER COLUMN id SET DEFAULT nextval('public.offerings_id_seq'::regclass);

ALTER TABLE ONLY public.reservations ALTER COLUMN id SET DEFAULT nextval('public.reservations_id_seq'::regclass);

ALTER TABLE ONLY public.schedule_blocks ALTER COLUMN id SET DEFAULT nextval('public.schedule_blocks_id_seq'::regclass);

ALTER TABLE ONLY public.booking_quotes
    ADD CONSTRAINT booking_quotes_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.booking_submissions
    ADD CONSTRAINT booking_submissions_pkey PRIMARY KEY (account_key, submission_key);

ALTER TABLE ONLY public.companies
    ADD CONSTRAINT companies_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.company_customer_blocks
    ADD CONSTRAINT company_customer_blocks_company_id_customer_id_key UNIQUE (company_id, customer_id);

ALTER TABLE ONLY public.company_customer_blocks
    ADD CONSTRAINT company_customer_blocks_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.company_customers
    ADD CONSTRAINT company_customers_company_id_user_id_key UNIQUE (company_id, user_id);

ALTER TABLE ONLY public.company_customers
    ADD CONSTRAINT company_customers_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.company_employees
    ADD CONSTRAINT company_employees_company_id_user_id_key UNIQUE (company_id, user_id);

ALTER TABLE ONLY public.company_employees
    ADD CONSTRAINT company_employees_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.customer_accounts
    ADD CONSTRAINT customer_accounts_phone_number_key UNIQUE (phone_number);

ALTER TABLE ONLY public.customer_accounts
    ADD CONSTRAINT customer_accounts_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.employee_offerings
    ADD CONSTRAINT employee_offerings_employee_id_offering_id_key UNIQUE (employee_id, offering_id);

ALTER TABLE ONLY public.employee_offerings
    ADD CONSTRAINT employee_offerings_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.employee_schedule_overrides
    ADD CONSTRAINT employee_schedule_overrides_company_id_employee_id_schedule_key UNIQUE (company_id, employee_id, schedule_date);

ALTER TABLE ONLY public.employee_schedule_overrides
    ADD CONSTRAINT employee_schedule_overrides_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.employee_work_schedules
    ADD CONSTRAINT employee_work_schedules_company_id_employee_id_day_of_week_key UNIQUE (company_id, employee_id, day_of_week);

ALTER TABLE ONLY public.employee_work_schedules
    ADD CONSTRAINT employee_work_schedules_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.no_show_penalties
    ADD CONSTRAINT no_show_penalties_pkey PRIMARY KEY (reservation_id);

ALTER TABLE ONLY public.notification_deliveries
    ADD CONSTRAINT notification_deliveries_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.notification_delivery_reservations
    ADD CONSTRAINT notification_delivery_reservations_pkey PRIMARY KEY (delivery_id, reservation_id);

ALTER TABLE ONLY public.offering_categories
    ADD CONSTRAINT offering_categories_company_id_name_key UNIQUE (company_id, name);

ALTER TABLE ONLY public.offering_categories
    ADD CONSTRAINT offering_categories_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.offering_images
    ADD CONSTRAINT offering_images_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.offerings
    ADD CONSTRAINT offerings_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.reservation_audit
    ADD CONSTRAINT reservation_audit_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.reservation_commands
    ADD CONSTRAINT reservation_commands_pkey PRIMARY KEY (reservation_id, account_key, command_key);

ALTER TABLE ONLY public.reservations
    ADD CONSTRAINT reservations_company_customer_no_overlap EXCLUDE USING gist (company_id WITH =, customer_id WITH =, tstzrange(start_time, end_time) WITH &&) WHERE (((status)::text <> ALL ((ARRAY['CANCELLED'::character varying, 'NO_SHOW'::character varying])::text[])));

ALTER TABLE ONLY public.reservations
    ADD CONSTRAINT reservations_company_employee_no_overlap EXCLUDE USING gist (company_id WITH =, employee_id WITH =, tstzrange(start_time, end_time) WITH &&) WHERE (((status)::text <> ALL ((ARRAY['CANCELLED'::character varying, 'NO_SHOW'::character varying])::text[])));

ALTER TABLE ONLY public.reservations
    ADD CONSTRAINT reservations_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.schedule_blocks
    ADD CONSTRAINT schedule_blocks_company_id_employee_id_tstzrange_excl EXCLUDE USING gist (company_id WITH =, employee_id WITH =, tstzrange(start_time, end_time) WITH &&);

ALTER TABLE ONLY public.schedule_blocks
    ADD CONSTRAINT schedule_blocks_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.staff_password_links
    ADD CONSTRAINT staff_password_links_pkey PRIMARY KEY (staff_account_id);

ALTER TABLE ONLY public.staff_password_links
    ADD CONSTRAINT staff_password_links_token_hash_key UNIQUE (token_hash);

CREATE INDEX current_no_show_penalties ON public.no_show_penalties USING btree (company_id, customer_id, appointment_date) WHERE active;

CREATE INDEX idx_employee_offerings_employee_id ON public.employee_offerings USING btree (employee_id);

CREATE INDEX idx_employee_offerings_offering_id ON public.employee_offerings USING btree (offering_id);

CREATE INDEX idx_employee_work_schedules_employee_id ON public.employee_work_schedules USING btree (employee_id);

CREATE INDEX idx_offering_categories_company_id ON public.offering_categories USING btree (company_id);

CREATE INDEX idx_offering_images_offering_id ON public.offering_images USING btree (offering_id);

CREATE INDEX idx_offerings_company_id ON public.offerings USING btree (company_id);

CREATE INDEX idx_reservations_company_employee_time ON public.reservations USING btree (company_id, employee_id, start_time);

CREATE INDEX idx_reservations_company_id ON public.reservations USING btree (company_id);

CREATE INDEX idx_reservations_employee_time ON public.reservations USING btree (employee_id, start_time);

CREATE INDEX idx_schedule_blocks_company_employee_time ON public.schedule_blocks USING btree (company_id, employee_id, start_time);

CREATE INDEX notification_due ON public.notification_deliveries USING btree (next_attempt_at) WHERE ((state)::text = ANY ((ARRAY['PENDING'::character varying, 'TEMPORARY_FAILURE'::character varying])::text[]));

CREATE UNIQUE INDEX notification_reminder_date ON public.notification_deliveries USING btree (reservation_id, appointment_date) WHERE ((kind)::text = 'REMINDER'::text);

CREATE UNIQUE INDEX notification_staff_revision ON public.notification_deliveries USING btree (reservation_id, reservation_revision, kind) WHERE ((kind)::text = ANY ((ARRAY['CREATE'::character varying, 'EDIT'::character varying, 'RESTORE'::character varying, 'CANCEL'::character varying])::text[]));

CREATE INDEX reservation_audit_history ON public.reservation_audit USING btree (reservation_id, id);

CREATE UNIQUE INDEX staff_company_login_email ON public.company_employees USING btree (company_id, login_email);

CREATE TRIGGER reservation_change_audit AFTER INSERT OR UPDATE ON public.reservations FOR EACH ROW EXECUTE FUNCTION public.audit_reservation_change();

ALTER TABLE ONLY public.employee_offerings
    ADD CONSTRAINT assignment_staff_membership FOREIGN KEY (company_id, employee_id) REFERENCES public.company_employees(company_id, user_id);

ALTER TABLE ONLY public.company_customer_blocks
    ADD CONSTRAINT blocks_local_profile FOREIGN KEY (company_id, customer_id) REFERENCES public.company_customers(company_id, user_id);

ALTER TABLE ONLY public.booking_quotes
    ADD CONSTRAINT booking_quotes_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id);

ALTER TABLE ONLY public.booking_quotes
    ADD CONSTRAINT booking_quotes_customer_id_fkey FOREIGN KEY (customer_id) REFERENCES public.customer_accounts(id);

ALTER TABLE ONLY public.booking_quotes
    ADD CONSTRAINT booking_quotes_service_id_fkey FOREIGN KEY (service_id) REFERENCES public.offerings(id);

ALTER TABLE ONLY public.booking_submissions
    ADD CONSTRAINT booking_submissions_reservation_id_fkey FOREIGN KEY (reservation_id) REFERENCES public.reservations(id);

ALTER TABLE ONLY public.company_customer_blocks
    ADD CONSTRAINT company_customer_blocks_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.company_customers
    ADD CONSTRAINT company_customers_account_fkey FOREIGN KEY (user_id) REFERENCES public.customer_accounts(id);

ALTER TABLE ONLY public.company_customers
    ADD CONSTRAINT company_customers_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.company_employees
    ADD CONSTRAINT company_employees_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.company_customer_blocks
    ADD CONSTRAINT customer_blocks_account_fkey FOREIGN KEY (customer_id) REFERENCES public.customer_accounts(id);

ALTER TABLE ONLY public.employee_offerings
    ADD CONSTRAINT employee_offerings_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.employee_offerings
    ADD CONSTRAINT employee_offerings_offering_id_fkey FOREIGN KEY (offering_id) REFERENCES public.offerings(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.employee_schedule_overrides
    ADD CONSTRAINT employee_schedule_overrides_company_id_employee_id_fkey FOREIGN KEY (company_id, employee_id) REFERENCES public.company_employees(company_id, user_id);

ALTER TABLE ONLY public.employee_work_schedules
    ADD CONSTRAINT employee_work_schedules_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.no_show_penalties
    ADD CONSTRAINT no_show_penalties_company_id_customer_id_fkey FOREIGN KEY (company_id, customer_id) REFERENCES public.company_customers(company_id, user_id);

ALTER TABLE ONLY public.no_show_penalties
    ADD CONSTRAINT no_show_penalties_reservation_id_fkey FOREIGN KEY (reservation_id) REFERENCES public.reservations(id);

ALTER TABLE ONLY public.notification_deliveries
    ADD CONSTRAINT notification_deliveries_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id);

ALTER TABLE ONLY public.notification_deliveries
    ADD CONSTRAINT notification_deliveries_customer_id_fkey FOREIGN KEY (customer_id) REFERENCES public.customer_accounts(id);

ALTER TABLE ONLY public.notification_deliveries
    ADD CONSTRAINT notification_deliveries_reservation_id_fkey FOREIGN KEY (reservation_id) REFERENCES public.reservations(id);

ALTER TABLE ONLY public.notification_delivery_reservations
    ADD CONSTRAINT notification_delivery_reservations_delivery_id_fkey FOREIGN KEY (delivery_id) REFERENCES public.notification_deliveries(id);

ALTER TABLE ONLY public.notification_delivery_reservations
    ADD CONSTRAINT notification_delivery_reservations_reservation_id_fkey FOREIGN KEY (reservation_id) REFERENCES public.reservations(id);

ALTER TABLE ONLY public.offering_categories
    ADD CONSTRAINT offering_categories_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.offering_images
    ADD CONSTRAINT offering_images_offering_id_fkey FOREIGN KEY (offering_id) REFERENCES public.offerings(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.offerings
    ADD CONSTRAINT offerings_category_id_fkey FOREIGN KEY (category_id) REFERENCES public.offering_categories(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.offerings
    ADD CONSTRAINT offerings_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.reservation_audit
    ADD CONSTRAINT reservation_audit_reservation_id_fkey FOREIGN KEY (reservation_id) REFERENCES public.reservations(id);

ALTER TABLE ONLY public.reservation_commands
    ADD CONSTRAINT reservation_commands_reservation_id_fkey FOREIGN KEY (reservation_id) REFERENCES public.reservations(id);

ALTER TABLE ONLY public.reservations
    ADD CONSTRAINT reservation_staff_membership FOREIGN KEY (company_id, employee_id) REFERENCES public.company_employees(company_id, user_id);

ALTER TABLE ONLY public.reservations
    ADD CONSTRAINT reservations_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.reservations
    ADD CONSTRAINT reservations_customer_account_fkey FOREIGN KEY (customer_id) REFERENCES public.customer_accounts(id);

ALTER TABLE ONLY public.reservations
    ADD CONSTRAINT reservations_service_id_fkey FOREIGN KEY (service_id) REFERENCES public.offerings(id);

ALTER TABLE ONLY public.schedule_blocks
    ADD CONSTRAINT schedule_blocks_company_id_employee_id_fkey FOREIGN KEY (company_id, employee_id) REFERENCES public.company_employees(company_id, user_id) ON DELETE CASCADE;

ALTER TABLE ONLY public.staff_password_links
    ADD CONSTRAINT staff_password_links_staff_account_id_fkey FOREIGN KEY (staff_account_id) REFERENCES public.company_employees(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.employee_work_schedules
    ADD CONSTRAINT weekly_staff_membership FOREIGN KEY (company_id, employee_id) REFERENCES public.company_employees(company_id, user_id);

-- OTP delivery is durable without creating an unverified customer identity or salon profile.
CREATE TABLE public.otp_deliveries (
    id uuid PRIMARY KEY,
    phone_number varchar(20) NOT NULL,
    code varchar(6),
    state varchar(30) NOT NULL DEFAULT 'PENDING'
        CHECK (state IN ('PENDING','SENDING','ACCEPTED','TEMPORARY_FAILURE','AMBIGUOUS','DISCARDED')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    next_attempt_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);
CREATE INDEX otp_deliveries_due ON public.otp_deliveries(next_attempt_at)
    WHERE state IN ('PENDING','TEMPORARY_FAILURE');
