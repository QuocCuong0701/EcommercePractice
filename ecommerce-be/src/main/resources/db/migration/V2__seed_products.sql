INSERT INTO categories (id, name, slug)
VALUES (gen_random_uuid(), 'Điện thoại', 'dien-thoai'),
       (gen_random_uuid(), 'Laptop', 'laptop'),
       (gen_random_uuid(), 'Phụ kiện', 'phu-kien');

INSERT INTO products (id, category_id, name, slug, price, original_price, description, images)
SELECT gen_random_uuid(), c.id,
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
SELECT id, 100 FROM products WHERE slug = 'iphone-15-pro';