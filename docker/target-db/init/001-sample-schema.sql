USE sample_store;

CREATE TABLE customers (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    full_name VARCHAR(120) NOT NULL,
    email VARCHAR(160) NOT NULL UNIQUE,
    city VARCHAR(80),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE orders (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    customer_id BIGINT NOT NULL,
    total_amount DECIMAL(12, 2) NOT NULL,
    status VARCHAR(30) NOT NULL,
    ordered_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id) REFERENCES customers(id)
);

INSERT INTO customers (full_name, email, city) VALUES
    ('Nguyễn An', 'an@example.com', 'Hồ Chí Minh'),
    ('Trần Bình', 'binh@example.com', 'Đà Nẵng'),
    ('Lê Chi', 'chi@example.com', 'Hà Nội');

INSERT INTO orders (customer_id, total_amount, status) VALUES
    (1, 1250000, 'PAID'),
    (1, 420000, 'PENDING'),
    (2, 890000, 'PAID');
