CREATE TABLE users
(
    id            UUID PRIMARY KEY             DEFAULT gen_random_uuid(),
    email         VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255)        NOT NULL, -- BCrypt
    full_name     VARCHAR(100),
    phone         VARCHAR(20),
    role          VARCHAR(20)         NOT NULL DEFAULT 'CUSTOMER',
    status        VARCHAR(20)         NOT NULL DEFAULT 'ACTIVE',
    created_at    TIMESTAMP           NOT NULL DEFAULT now()
);

CREATE TABLE addresses
(
    id         UUID PRIMARY KEY      DEFAULT gen_random_uuid(),
    user_id    UUID         NOT NULL REFERENCES users (id),
    full_name  VARCHAR(100) NOT NULL,
    phone      VARCHAR(20)  NOT NULL,
    address    TEXT         NOT NULL,
    province   VARCHAR(100) NOT NULL,
    district   VARCHAR(100) NOT NULL,
    is_default BOOLEAN      NOT NULL DEFAULT false,
    created_at TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX idx_users_email ON users (email);
CREATE INDEX idx_addresses_user ON addresses (user_id);