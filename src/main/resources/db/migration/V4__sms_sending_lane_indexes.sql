-- Bound active-lane lookups independently of the growing terminal delivery history.
CREATE INDEX notification_sending_lane ON notification_deliveries (company_id, customer_id)
    WHERE state = 'SENDING';
CREATE INDEX otp_sending_phone ON otp_deliveries (phone_number)
    WHERE state = 'SENDING';
