CREATE INDEX company_customer_read_page
    ON company_customers (company_id, last_name, first_name, id)
    WHERE first_booking_at IS NOT NULL;

CREATE INDEX reservation_customer_read_page
    ON reservations (company_id, customer_id, start_time DESC, id DESC)
    WHERE archived = false;
