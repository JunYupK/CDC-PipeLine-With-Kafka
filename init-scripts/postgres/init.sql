CREATE TABLE orders (
    order_id SERIAL PRIMARY KEY,
    customer_id INTEGER,
    product_id INTEGER,
    quantity INTEGER,
    price DECIMAL(10,2),
    order_date DATE
);

INSERT INTO orders (customer_id, product_id, quantity, price, order_date)
VALUES 
    (1, 101, 2, 29.99, '2023-12-01'),
    (2, 102, 1, 49.99, '2023-12-01'),
    (1, 103, 3, 19.99, '2023-12-02'),
    (3, 101, 1, 29.99, '2023-12-02'),
    (2, 104, 2, 39.99, '2023-12-03');