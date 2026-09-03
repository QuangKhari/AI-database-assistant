USE ai_db_assistant_sample;

INSERT INTO customers (full_name, email, city, created_at) VALUES
('Nguyen Van An',   'an.nguyen@example.com',   'Ha Noi',    '2025-01-05 09:00:00'),
('Tran Thi Binh',   'binh.tran@example.com',   'Ho Chi Minh','2025-01-12 10:30:00'),
('Le Van Cuong',    'cuong.le@example.com',    'Da Nang',   '2025-02-01 14:15:00'),
('Pham Thi Dung',   'dung.pham@example.com',   'Ha Noi',    '2025-02-20 08:45:00'),
('Hoang Van Em',    'em.hoang@example.com',    'Can Tho',   '2025-03-03 11:00:00'),
('Vu Thi Giang',    'giang.vu@example.com',    'Ha Noi',    '2025-03-15 16:20:00'),
('Do Van Hai',      'hai.do@example.com',      'Ho Chi Minh','2025-04-02 09:10:00'),
('Bui Thi Kim',     'kim.bui@example.com',     'Hai Phong', '2025-04-18 13:40:00');

INSERT INTO products (product_name, category, unit_price, stock_quantity) VALUES
('Ao thun basic',        'Thoi trang',      150000, 200),
('Quan jean slimfit',    'Thoi trang',      450000, 120),
('Giay sneaker trang',   'Giay dep',        690000, 80),
('Balo laptop 15 inch',  'Phu kien',        390000, 60),
('Tai nghe bluetooth',   'Dien tu',         590000, 100),
('Chuot khong day',      'Dien tu',         250000, 150),
('Ban phim co',          'Dien tu',         890000, 45),
('Binh giu nhiet 500ml', 'Gia dung',        180000, 300),
('Non luoi trai',        'Thoi trang',      120000, 220),
('Vi da nam',            'Phu kien',        320000, 90);

INSERT INTO orders (customer_id, order_date, status) VALUES
(1, '2025-05-01', 'COMPLETED'),
(1, '2025-06-10', 'COMPLETED'),
(2, '2025-05-03', 'COMPLETED'),
(3, '2025-05-15', 'COMPLETED'),
(3, '2025-07-02', 'CANCELLED'),
(4, '2025-05-20', 'COMPLETED'),
(5, '2025-06-01', 'COMPLETED'),
(6, '2025-06-05', 'COMPLETED'),
(6, '2025-07-11', 'COMPLETED'),
(7, '2025-06-18', 'COMPLETED'),
(8, '2025-06-25', 'COMPLETED'),
(2, '2025-07-01', 'COMPLETED'),
(4, '2025-07-08', 'COMPLETED'),
(5, '2025-07-15', 'CANCELLED'),
(8, '2025-07-20', 'COMPLETED');

INSERT INTO order_items (order_id, product_id, quantity, unit_price) VALUES
(1, 1, 2, 150000), (1, 3, 1, 690000),
(2, 5, 1, 590000), (2, 6, 2, 250000),
(3, 2, 1, 450000),
(4, 4, 1, 390000), (4, 9, 2, 120000),
(5, 7, 1, 890000),
(6, 1, 3, 150000), (6, 10, 1, 320000),
(7, 3, 1, 690000),
(8, 8, 4, 180000),
(9, 2, 2, 450000),
(10, 5, 1, 590000), (10, 6, 1, 250000),
(11, 9, 1, 120000),
(12, 1, 1, 150000), (12, 7, 1, 890000),
(13, 4, 2, 390000),
(14, 3, 1, 690000),
(15, 10, 3, 320000), (15, 8, 2, 180000);