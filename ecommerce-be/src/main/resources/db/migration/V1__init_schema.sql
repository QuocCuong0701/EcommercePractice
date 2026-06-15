-- V1__init_schema.sql
CREATE TABLE IF NOT EXISTS categories
(
    id         UUID PRIMARY KEY             DEFAULT gen_random_uuid(),
    name       VARCHAR(100)        NOT NULL,
    slug       VARCHAR(100) UNIQUE NOT NULL,
    parent_id  UUID REFERENCES categories (id),
    created_at TIMESTAMP           NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS products
(
    id             UUID PRIMARY KEY             DEFAULT gen_random_uuid(),
    category_id    UUID REFERENCES categories (id),
    name           VARCHAR(255)        NOT NULL,
    slug           VARCHAR(255) UNIQUE NOT NULL,
    description    TEXT,
    price          NUMERIC(15, 2)      NOT NULL,
    original_price NUMERIC(15, 2),                            -- giá gốc để hiển thị % giảm
    images         JSONB               NOT NULL DEFAULT '[]', -- ["url1", "url2"]
    attributes     JSONB               NOT NULL DEFAULT '{}', -- {"color":"red","size":"L"}
    status         VARCHAR(20)         NOT NULL DEFAULT 'ACTIVE',
    version        BIGINT              NOT NULL DEFAULT 0,
    created_at     TIMESTAMP           NOT NULL DEFAULT now(),
    updated_at     TIMESTAMP           NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS inventory
(
    id         UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
    product_id UUID UNIQUE NOT NULL REFERENCES products (id),
    quantity   INT         NOT NULL DEFAULT 0,
    reserved   INT         NOT NULL DEFAULT 0, -- đã reserved nhưng chưa confirmed
    version    BIGINT      NOT NULL DEFAULT 0, -- Optimistic Lock
    updated_at TIMESTAMP   NOT NULL DEFAULT now(),

    CONSTRAINT quantity_non_negative CHECK (quantity >= 0),
    CONSTRAINT reserved_non_negative CHECK (reserved >= 0),
    CONSTRAINT reserved_lte_quantity CHECK (reserved <= quantity)
);

-- Indexes
CREATE INDEX idx_products_category ON products (category_id);
CREATE INDEX idx_products_status ON products (status);
CREATE INDEX idx_products_price ON products (price);
CREATE INDEX idx_products_created ON products (created_at DESC);