-- V4__orders.sql
CREATE TABLE orders
(
    id               UUID PRIMARY KEY            DEFAULT gen_random_uuid(),
    order_number     VARCHAR(30) UNIQUE NOT NULL, -- ORD-20240115-001234
    user_id          UUID               NOT NULL REFERENCES users (id),
    status           VARCHAR(30)        NOT NULL DEFAULT 'PENDING_PAYMENT',
    subtotal         NUMERIC(15, 2)     NOT NULL,
    shipping_fee     NUMERIC(15, 2)     NOT NULL DEFAULT 0,
    discount         NUMERIC(15, 2)     NOT NULL DEFAULT 0,
    total            NUMERIC(15, 2)     NOT NULL,
    shipping_address JSONB              NOT NULL, -- snapshot address lúc đặt hàng
    idempotency_key  VARCHAR(100) UNIQUE,
    notes            TEXT,
    created_at       TIMESTAMP          NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP          NOT NULL DEFAULT now()
);

/*
Order status flow:
PENDING_PAYMENT → PAID → PROCESSING → SHIPPED → DELIVERED
                ↘ CANCELLED (bởi user hoặc timeout)
                          ↘ REFUNDED
*/

CREATE TABLE order_items
(
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id      UUID           NOT NULL REFERENCES orders (id),
    product_id    UUID           NOT NULL REFERENCES products (id),
    product_name  VARCHAR(255)   NOT NULL, -- snapshot tên lúc đặt
    product_image VARCHAR(500),
    quantity      INT            NOT NULL,
    unit_price    NUMERIC(15, 2) NOT NULL, -- snapshot giá lúc đặt
    subtotal      NUMERIC(15, 2) NOT NULL
);

CREATE TABLE payments
(
    id                     UUID PRIMARY KEY        DEFAULT gen_random_uuid(),
    order_id               UUID           NOT NULL REFERENCES orders (id),
    amount                 NUMERIC(15, 2) NOT NULL,
    method                 VARCHAR(30)    NOT NULL, -- VNPAY, MOMO, COD, BANK_TRANSFER
    status                 VARCHAR(20)    NOT NULL DEFAULT 'PENDING',
    gateway_transaction_id VARCHAR(100),
    idempotency_key        VARCHAR(100) UNIQUE,
    metadata               JSONB,
    created_at             TIMESTAMP      NOT NULL DEFAULT now(),
    updated_at             TIMESTAMP      NOT NULL DEFAULT now()
);

CREATE INDEX idx_orders_user ON orders (user_id);
CREATE INDEX idx_orders_status ON orders (status);
CREATE INDEX idx_orders_number ON orders (order_number);
CREATE INDEX idx_payments_order ON payments(order_id);
CREATE INDEX idx_payments_idempotency ON payments(idempotency_key);
CREATE INDEX idx_order_items_order ON order_items(order_id);
CREATE INDEX idx_orders_created ON orders(created_at);