-- V2__seed_products.sql
INSERT INTO categories (name, slug)
VALUES ('Điện thoại', 'dien-thoai'),
       ('Laptop', 'laptop'),
       ('Phụ kiện', 'phu-kien');

INSERT INTO products (category_id, name, slug, price, original_price, description, images)
SELECT c.id,
       'iPhone 15 Pro',
       'iphone-15-pro',
       29990000,
       32000000,
       'Chip A17 Pro, camera 48MP',
       '["https://placehold.co/400x400"]'
FROM categories c
WHERE c.slug = 'dien-thoai';

-- Thêm inventory tương ứng
INSERT INTO inventory (product_id, quantity)
SELECT id, 100
FROM products
WHERE slug = 'iphone-15-pro';