CREATE TABLE IF NOT EXISTS order_fulfillment_status (
    status_id INTEGER PRIMARY KEY,
    status_code VARCHAR(64) NOT NULL UNIQUE,
    display_name VARCHAR(128) NOT NULL,
    description VARCHAR(512) NOT NULL,
    terminal_status BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE IF NOT EXISTS customer_order (
    order_id VARCHAR(64) PRIMARY KEY,
    customer_id VARCHAR(64) NOT NULL,
    order_start_date TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_customer_order_customer_id
    ON customer_order (customer_id);

CREATE TABLE IF NOT EXISTS order_fulfillment (
    fulfillment_id INTEGER PRIMARY KEY AUTOINCREMENT,
    order_id VARCHAR(64) NOT NULL,
    status_id INTEGER NOT NULL,
    status_update_timestamp TIMESTAMP NOT NULL,
    details VARCHAR(512),
    FOREIGN KEY (order_id) REFERENCES customer_order(order_id),
    FOREIGN KEY (status_id) REFERENCES order_fulfillment_status(status_id)
);

CREATE INDEX IF NOT EXISTS idx_order_fulfillment_order_timestamp
    ON order_fulfillment (order_id, status_update_timestamp);
