ALTER TABLE services RENAME TO offerings;
ALTER TABLE service_categories RENAME TO offering_categories;
ALTER TABLE service_images RENAME TO offering_images;
ALTER TABLE employee_services RENAME TO employee_offerings;

ALTER TABLE offering_images RENAME COLUMN service_id TO offering_id;
ALTER TABLE employee_offerings RENAME COLUMN service_id TO offering_id;
