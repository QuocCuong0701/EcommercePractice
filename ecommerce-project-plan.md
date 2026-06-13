# Kế hoạch xây dựng hệ thống E-Commerce chịu tải cao
> Stack: Java 21 · Spring Boot 3 · PostgreSQL · Redis · Kafka · Elasticsearch · Nginx · Docker

---

## Triết lý thiết kế

Mỗi giai đoạn kết thúc bằng một hệ thống **chạy được, dùng được, đo được**.  
Không có giai đoạn nào chỉ là "setup" hay "chuẩn bị" — luôn có output thật.

```
Phase 0 → Infra chạy, health check OK
Phase 1 → Browse sản phẩm được
Phase 2 → Đăng ký, đăng nhập, thêm vào giỏ hàng
Phase 3 → Đặt hàng, thanh toán (mock)
Phase 4 → Flash sale 10.000 người cùng mua
Phase 5 → Production-ready: monitor, alert, load test pass
```

---

## Tech Stack tổng quan

| Tầng | Công nghệ | Lý do chọn |
|---|---|---|
| **Language** | Java 21 | Virtual Threads — concurrency cao mà code đơn giản |
| **Framework** | Spring Boot 3.2 | Ecosystem đầy đủ, Banking/Fintech standard |
| **Frontend** | Plain HTML/JS + Nginx | Tĩnh trên CDN, không tốn app server resource |
| **Primary DB** | PostgreSQL 16 | ACID, JSON support, pg_stat_statements |
| **Cache / Session** | Redis 7 | Session stateless, Cart, Idempotency, Rate limit |
| **Message Queue** | Kafka 3.6 | Order events, Inventory update, Email, Audit |
| **Search** | Elasticsearch 8 | Full-text search sản phẩm (Phase 4+) |
| **Reverse Proxy** | Nginx | Static files, Load balancing, SSL termination |
| **Monitor** | Prometheus + Grafana | Metrics, Alert |
| **Load Test** | k6 | Script-based, CI/CD friendly |
| **Container** | Docker + Docker Compose | Dev environment nhất quán |
| **Auth** | Spring Security + JWT | Stateless — scale horizontal không cần sticky session |
| **API Docs** | SpringDoc OpenAPI 3 | Auto-generate từ code |
| **Migration** | Flyway | Version control cho DB schema |

---

## Kiến trúc tổng thể

```
                        ┌──────────┐
                        │  Client  │ (Browser)
                        └────┬─────┘
                             │ HTTPS
                        ┌────▼─────┐
                        │  Nginx   │ ← Static files (HTML/JS/CSS)
                        │          │ ← SSL termination
                        │          │ ← Rate limiting
                        └────┬─────┘
                             │ /api/*
              ┌──────────────▼──────────────┐
              │     Spring Boot App         │
              │  (Java 21 Virtual Threads)  │
              │                             │
              │  ┌─────────┐ ┌──────────┐  │
              │  │ Products│ │  Orders  │  │
              │  │  Cart   │ │  Users   │  │
              │  │ Payment │ │  Search  │  │
              │  └────┬────┘ └────┬─────┘  │
              └───────┼───────────┼─────────┘
                      │           │
         ┌────────────┼───────────┼────────────┐
         │            │           │            │
    ┌────▼───┐  ┌─────▼──┐  ┌────▼────┐  ┌───▼──┐
    │Postgres│  │ Redis  │  │  Kafka  │  │  ES  │
    │        │  │        │  │         │  │      │
    │Orders  │  │Session │  │order.   │  │prod  │
    │Products│  │Cart    │  │events   │  │search│
    │Users   │  │Idempot.│  │email    │  │      │
    │Inventory│ │RateLimit│ │audit    │  │      │
    └────────┘  └────────┘  └─────────┘  └──────┘
                                │
                    ┌───────────┼────────────┐
                    │           │            │
             ┌──────▼──┐ ┌─────▼────┐ ┌────▼────┐
             │  Email  │ │Inventory │ │  Audit  │
             │ Service │ │ Service  │ │ Service │
             └─────────┘ └──────────┘ └─────────┘
```

---

## Cấu trúc project

```
ecommerce/
├── backend/
│   ├── src/main/java/com/ecommerce/
│   │   ├── config/
│   │   │   ├── SecurityConfig.java
│   │   │   ├── RedisConfig.java
│   │   │   ├── KafkaConfig.java
│   │   │   └── SwaggerConfig.java
│   │   ├── domain/
│   │   │   ├── product/
│   │   │   │   ├── Product.java
│   │   │   │   ├── ProductRepository.java
│   │   │   │   ├── ProductService.java
│   │   │   │   └── ProductController.java
│   │   │   ├── user/
│   │   │   ├── cart/
│   │   │   ├── order/
│   │   │   ├── inventory/
│   │   │   └── payment/
│   │   ├── event/              # Kafka events
│   │   ├── consumer/           # Kafka consumers
│   │   ├── exception/
│   │   ├── dto/
│   │   └── EcommerceApplication.java
│   ├── src/main/resources/
│   │   ├── db/migration/       # Flyway scripts
│   │   │   ├── V1__init_schema.sql
│   │   │   ├── V2__seed_products.sql
│   │   │   └── V3__add_indexes.sql
│   │   └── application.yml
│   └── pom.xml
├── frontend/
│   ├── public/
│   │   ├── index.html
│   │   ├── products.html
│   │   ├── cart.html
│   │   └── checkout.html
│   ├── css/
│   │   └── main.css
│   └── js/
│       ├── api.js              # Fetch wrapper + JWT interceptor
│       ├── cart.js
│       └── product.js
├── nginx/
│   └── nginx.conf
├── monitoring/
│   ├── prometheus.yml
│   ├── alerts.yml
│   └── grafana/
│       └── dashboards/
├── k6/
│   ├── smoke-test.js
│   ├── load-test.js
│   └── stress-test.js
└── docker-compose.yml
```

---

## Phase 0 — Infrastructure & Project Scaffolding

> **Deliverable:** `docker-compose up` → tất cả services healthy. Spring Boot start, kết nối được DB.

**Thời gian ước tính:** 1–2 ngày

### 0.1 Docker Compose — toàn bộ infra

```yaml
# docker-compose.yml
version: '3.8'

services:
  # ── Databases ──────────────────────────────────────
  postgres:
    image: postgres:16
    environment:
      POSTGRES_DB: ecommerce
      POSTGRES_USER: ecommerce
      POSTGRES_PASSWORD: ecommerce123
    ports:
      - "5432:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ecommerce"]
      interval: 10s
      timeout: 5s
      retries: 5

  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"
    command: redis-server --maxmemory 256mb --maxmemory-policy allkeys-lru
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s

  # ── Kafka ──────────────────────────────────────────
  zookeeper:
    image: confluentinc/cp-zookeeper:7.5.0
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181

  kafka:
    image: confluentinc/cp-kafka:7.5.0
    depends_on: [zookeeper]
    ports:
      - "9092:9092"
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_AUTO_CREATE_TOPICS_ENABLE: true

  kafka-ui:
    image: provectuslabs/kafka-ui:latest
    ports:
      - "8090:8080"
    environment:
      KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS: kafka:9092

  # ── Search ─────────────────────────────────────────
  elasticsearch:
    image: elasticsearch:8.11.0
    environment:
      - discovery.type=single-node
      - xpack.security.enabled=false
      - ES_JAVA_OPTS=-Xms512m -Xmx512m
    ports:
      - "9200:9200"
    volumes:
      - esdata:/usr/share/elasticsearch/data

  # ── Monitoring ─────────────────────────────────────
  prometheus:
    image: prom/prometheus:v2.48.0
    ports:
      - "9090:9090"
    volumes:
      - ./monitoring/prometheus.yml:/etc/prometheus/prometheus.yml

  grafana:
    image: grafana/grafana:10.2.0
    ports:
      - "3000:3000"
    environment:
      GF_SECURITY_ADMIN_PASSWORD: admin
    volumes:
      - grafana-data:/var/lib/grafana

  # ── Frontend ───────────────────────────────────────
  nginx:
    image: nginx:alpine
    ports:
      - "80:80"
    volumes:
      - ./frontend/public:/usr/share/nginx/html
      - ./nginx/nginx.conf:/etc/nginx/nginx.conf

volumes:
  pgdata:
  esdata:
  grafana-data:
```

### 0.2 application.yml đầy đủ

```yaml
spring:
  application:
    name: ecommerce-api

  datasource:
    url: jdbc:postgresql://localhost:5432/ecommerce
    username: ecommerce
    password: ecommerce123
    hikari:
      pool-name: EcommercePool
      maximum-pool-size: 10        # (core_count * 2) + 1
      minimum-idle: 3
      connection-timeout: 3000
      idle-timeout: 600000
      max-lifetime: 1800000

  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: false
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
        default_batch_fetch_size: 50  # tránh N+1 query

  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true

  data:
    redis:
      host: localhost
      port: 6379
      timeout: 2000ms
      lettuce:
        pool:
          max-active: 10
          min-idle: 2

  kafka:
    bootstrap-servers: localhost:9092
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
      acks: all
      retries: 3
      properties:
        enable.idempotence: true
    consumer:
      group-id: ecommerce-service
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: "com.ecommerce.event"
    listener:
      ack-mode: MANUAL_IMMEDIATE

# JWT
app:
  jwt:
    secret: "your-secret-key-min-256-bits-long-please-change-this"
    expiration-ms: 86400000    # 24 giờ
    refresh-expiration-ms: 604800000  # 7 ngày

# Actuator + Prometheus
management:
  endpoints:
    web:
      exposure:
        include: health, prometheus, metrics, info
  metrics:
    tags:
      application: ecommerce-api
    distribution:
      percentiles-histogram:
        http.server.requests: true
      percentiles:
        http.server.requests: 0.5, 0.95, 0.99
```

### 0.3 Nginx config

```nginx
# nginx/nginx.conf
events { worker_connections 1024; }

http {
    include       /etc/nginx/mime.types;
    default_type  application/octet-stream;

    # Gzip compression
    gzip on;
    gzip_types text/html text/css application/javascript application/json;

    # Cache static files
    server {
        listen 80;

        # Static files — serve trực tiếp, không qua app server
        location / {
            root   /usr/share/nginx/html;
            index  index.html;
            try_files $uri $uri/ /index.html;

            # Cache HTML 5 phút, assets 1 năm
            location ~* \.(js|css|png|jpg|ico)$ {
                expires 1y;
                add_header Cache-Control "public, immutable";
            }
        }

        # API calls — proxy đến Spring Boot
        location /api/ {
            proxy_pass http://host.docker.internal:8080;
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;

            # Rate limiting — tránh abuse
            limit_req zone=api_limit burst=20 nodelay;
        }
    }

    limit_req_zone $binary_remote_addr zone=api_limit:10m rate=30r/s;
}
```

### 0.4 Checklist hoàn thành Phase 0

```
□ docker-compose up -d → tất cả containers healthy
□ curl http://localhost:8080/actuator/health → {"status":"UP"}
□ curl http://localhost:9200/_cluster/health → Elasticsearch green/yellow
□ redis-cli ping → PONG
□ http://localhost:8090 → Kafka UI mở được
□ http://localhost:3000 → Grafana login được
□ Spring Boot start không lỗi, Flyway migrate V1 thành công
```

---

## Phase 1 — Product Catalog

> **Deliverable:** User mở browser → thấy danh sách sản phẩm → xem chi tiết → tìm kiếm theo tên.  
> Backend: REST API cho products. Frontend: 2 trang HTML tĩnh gọi API.

**Thời gian ước tính:** 3–4 ngày

### 1.1 Database Schema (Flyway V1)

```sql
-- V1__init_schema.sql
CREATE TABLE categories (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(100) NOT NULL,
    slug        VARCHAR(100) UNIQUE NOT NULL,
    parent_id   UUID REFERENCES categories(id),
    created_at  TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE products (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    category_id     UUID REFERENCES categories(id),
    name            VARCHAR(255) NOT NULL,
    slug            VARCHAR(255) UNIQUE NOT NULL,
    description     TEXT,
    price           NUMERIC(15, 2) NOT NULL,
    original_price  NUMERIC(15, 2),          -- giá gốc để hiển thị % giảm
    images          JSONB NOT NULL DEFAULT '[]',  -- ["url1", "url2"]
    attributes      JSONB NOT NULL DEFAULT '{}',  -- {"color":"red","size":"L"}
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version         BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE inventory (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id  UUID UNIQUE NOT NULL REFERENCES products(id),
    quantity    INT NOT NULL DEFAULT 0,
    reserved    INT NOT NULL DEFAULT 0,       -- đã reserved nhưng chưa confirmed
    version     BIGINT NOT NULL DEFAULT 0,    -- Optimistic Lock
    updated_at  TIMESTAMP NOT NULL DEFAULT now(),

    CONSTRAINT quantity_non_negative CHECK (quantity >= 0),
    CONSTRAINT reserved_non_negative CHECK (reserved >= 0),
    CONSTRAINT reserved_lte_quantity  CHECK (reserved <= quantity)
);

-- Indexes
CREATE INDEX idx_products_category   ON products(category_id);
CREATE INDEX idx_products_status     ON products(status);
CREATE INDEX idx_products_price      ON products(price);
CREATE INDEX idx_products_created    ON products(created_at DESC);
```

```sql
-- V2__seed_products.sql
INSERT INTO categories (name, slug) VALUES
  ('Điện thoại', 'dien-thoai'),
  ('Laptop',     'laptop'),
  ('Phụ kiện',   'phu-kien');

INSERT INTO products (category_id, name, slug, price, original_price, description, images)
SELECT c.id, 'iPhone 15 Pro', 'iphone-15-pro', 29990000, 32000000,
  'Chip A17 Pro, camera 48MP', '["https://placehold.co/400x400"]'
FROM categories c WHERE c.slug = 'dien-thoai';

-- Thêm inventory tương ứng
INSERT INTO inventory (product_id, quantity)
SELECT id, 100 FROM products WHERE slug = 'iphone-15-pro';
```

### 1.2 Product Entity & Repository

```java
@Entity @Table(name = "products")
@Data @NoArgsConstructor
public class Product {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    private String name;
    private String slug;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal price;

    @Column(name = "original_price", precision = 15, scale = 2)
    private BigDecimal originalPrice;

    // JSONB — lưu mảng URL ảnh trực tiếp, không cần bảng riêng
    @Column(columnDefinition = "jsonb")
    @Convert(converter = StringListConverter.class)
    private List<String> images = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    private ProductStatus status = ProductStatus.ACTIVE;

    @Version private Long version;
    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime updatedAt = LocalDateTime.now();
}
```

```java
public interface ProductRepository extends JpaRepository<Product, UUID> {

    // Phân trang — KHÔNG dùng findAll() trả toàn bộ
    Page<Product> findByStatusOrderByCreatedAtDesc(
        ProductStatus status, Pageable pageable);

    Optional<Product> findBySlug(String slug);

    // Lọc theo category + price range
    @Query("""
        SELECT p FROM Product p
        WHERE p.status = 'ACTIVE'
        AND (:categoryId IS NULL OR p.category.id = :categoryId)
        AND (:minPrice IS NULL OR p.price >= :minPrice)
        AND (:maxPrice IS NULL OR p.price <= :maxPrice)
        ORDER BY p.createdAt DESC
        """)
    Page<Product> findWithFilters(
        @Param("categoryId") UUID categoryId,
        @Param("minPrice") BigDecimal minPrice,
        @Param("maxPrice") BigDecimal maxPrice,
        Pageable pageable
    );

    // Full-text search đơn giản với PostgreSQL ILIKE
    // (Phase 4 sẽ thay bằng Elasticsearch)
    @Query("SELECT p FROM Product p WHERE p.status = 'ACTIVE' " +
           "AND (LOWER(p.name) LIKE LOWER(CONCAT('%', :q, '%')) " +
           "OR LOWER(p.description) LIKE LOWER(CONCAT('%', :q, '%')))")
    Page<Product> search(@Param("q") String query, Pageable pageable);
}
```

### 1.3 Product Service

```java
@Service @RequiredArgsConstructor @Slf4j
public class ProductService {

    private final ProductRepository productRepo;
    private final RedisTemplate<String, Object> redisTemplate;

    private static final String CACHE_PREFIX = "product:";
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    public Page<ProductSummaryDto> list(int page, int size,
                                        UUID categoryId,
                                        BigDecimal minPrice,
                                        BigDecimal maxPrice) {
        Pageable pageable = PageRequest.of(page, size);
        return productRepo.findWithFilters(categoryId, minPrice, maxPrice, pageable)
            .map(ProductSummaryDto::from);
    }

    public ProductDetailDto getBySlug(String slug) {
        // Cache-aside pattern cho product detail
        String cacheKey = CACHE_PREFIX + slug;
        ProductDetailDto cached = (ProductDetailDto)
            redisTemplate.opsForValue().get(cacheKey);

        if (cached != null) return cached;

        ProductDetailDto dto = productRepo.findBySlug(slug)
            .map(ProductDetailDto::from)
            .orElseThrow(() -> new ProductNotFoundException("Product not found: " + slug));

        redisTemplate.opsForValue().set(cacheKey, dto, CACHE_TTL);
        return dto;
    }

    public Page<ProductSummaryDto> search(String query, int page, int size) {
        return productRepo.search(query, PageRequest.of(page, size))
            .map(ProductSummaryDto::from);
    }

    // Gọi khi product được update — invalidate cache
    public void evictCache(String slug) {
        redisTemplate.delete(CACHE_PREFIX + slug);
    }
}
```

### 1.4 REST API Endpoints

```
GET  /api/v1/products                   # list, phân trang, filter
GET  /api/v1/products/{slug}            # detail
GET  /api/v1/products/search?q=iphone  # tìm kiếm
GET  /api/v1/categories                 # list categories
```

### 1.5 Frontend — products.html

```javascript
// js/api.js — Fetch wrapper dùng cho toàn bộ app
const API_BASE = '/api/v1';

export async function apiFetch(path, options = {}) {
    const token = localStorage.getItem('accessToken');
    const headers = {
        'Content-Type': 'application/json',
        ...(token && { 'Authorization': `Bearer ${token}` }),
        ...options.headers,
    };

    const res = await fetch(API_BASE + path, { ...options, headers });

    // Auto refresh token nếu 401
    if (res.status === 401) {
        const refreshed = await refreshToken();
        if (refreshed) return apiFetch(path, options);
        window.location.href = '/login.html';
    }

    if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw new Error(err.message || 'API Error');
    }

    return res.json();
}
```

### 1.6 Checklist hoàn thành Phase 1

```
□ GET /api/v1/products trả về list có pagination
□ GET /api/v1/products/iphone-15-pro trả về detail
□ GET /api/v1/products/search?q=iphone tìm được
□ Cache hit sau request thứ 2 (xem Redis: redis-cli keys "product:*")
□ Mở http://localhost → trang sản phẩm hiển thị
□ Filter theo category, price range hoạt động
□ Response time < 50ms cho cached requests
```

---

## Phase 2 — Authentication + Cart

> **Deliverable:** Đăng ký tài khoản → đăng nhập → thêm sản phẩm vào giỏ → xem giỏ hàng.  
> JWT stateless — server không giữ session, scale horizontal thoải mái.

**Thời gian ước tính:** 3–4 ngày

### 2.1 Database Schema

```sql
-- V3__users_and_carts.sql
CREATE TABLE users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email           VARCHAR(255) UNIQUE NOT NULL,
    password_hash   VARCHAR(255) NOT NULL,  -- BCrypt
    full_name       VARCHAR(100),
    phone           VARCHAR(20),
    role            VARCHAR(20) NOT NULL DEFAULT 'CUSTOMER',
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE addresses (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id),
    full_name   VARCHAR(100) NOT NULL,
    phone       VARCHAR(20) NOT NULL,
    address     TEXT NOT NULL,
    province    VARCHAR(100) NOT NULL,
    district    VARCHAR(100) NOT NULL,
    is_default  BOOLEAN NOT NULL DEFAULT false,
    created_at  TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_users_email    ON users(email);
CREATE INDEX idx_addresses_user ON addresses(user_id);
```

Cart lưu trong **Redis** — không phải PostgreSQL. Lý do: cart thay đổi liên tục, không cần ACID, cần read/write cực nhanh, tự expire khi user không hoạt động.

```
Redis key: cart:{userId}
Type: Hash
Fields: {productId} → {quantity, price, name, image}  (JSON string)
TTL: 7 ngày (reset khi có activity)
```

### 2.2 JWT Auth Flow

```
POST /api/v1/auth/register   → tạo tài khoản, trả accessToken + refreshToken
POST /api/v1/auth/login      → trả accessToken + refreshToken
POST /api/v1/auth/refresh    → đổi refreshToken → accessToken mới
POST /api/v1/auth/logout     → blacklist refreshToken trong Redis

accessToken:  JWT, expire 1 giờ, lưu trong memory (không localStorage)
refreshToken: JWT, expire 7 ngày, lưu httpOnly cookie
```

```java
@Service @RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepo;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RedisTemplate<String, String> redisTemplate;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepo.existsByEmail(request.getEmail()))
            throw new EmailAlreadyExistsException("Email đã được sử dụng");

        User user = new User();
        user.setEmail(request.getEmail());
        // BCrypt tự thêm salt — KHÔNG tự hash bằng MD5/SHA
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setFullName(request.getFullName());
        userRepo.save(user);

        return generateTokens(user);
    }

    public AuthResponse login(LoginRequest request) {
        User user = userRepo.findByEmail(request.getEmail())
            .orElseThrow(() -> new BadCredentialsException("Email hoặc mật khẩu sai"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash()))
            throw new BadCredentialsException("Email hoặc mật khẩu sai");

        if (user.getStatus() != UserStatus.ACTIVE)
            throw new AccountDisabledException("Tài khoản bị khóa");

        return generateTokens(user);
    }

    public void logout(String refreshToken) {
        // Blacklist token trong Redis với TTL = thời gian còn lại của token
        String key = "blacklist:refresh:" + refreshToken;
        redisTemplate.opsForValue().set(key, "revoked",
            Duration.ofDays(7));
    }

    private AuthResponse generateTokens(User user) {
        String accessToken  = jwtService.generateAccessToken(user);
        String refreshToken = jwtService.generateRefreshToken(user);
        return new AuthResponse(accessToken, refreshToken, user.getEmail());
    }
}
```

### 2.3 Cart Service — Redis Hash

```java
@Service @RequiredArgsConstructor
public class CartService {

    private final RedisTemplate<String, String> redisTemplate;
    private final ProductService productService;
    private final ObjectMapper objectMapper;

    private static final Duration CART_TTL = Duration.ofDays(7);

    private String cartKey(UUID userId) { return "cart:" + userId; }

    public CartDto getCart(UUID userId) {
        Map<Object, Object> entries = redisTemplate.opsForHash()
            .entries(cartKey(userId));

        List<CartItemDto> items = entries.entrySet().stream()
            .map(e -> parseItem((String) e.getValue()))
            .filter(Objects::nonNull)
            .toList();

        BigDecimal total = items.stream()
            .map(i -> i.getPrice().multiply(BigDecimal.valueOf(i.getQuantity())))
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new CartDto(items, total, items.size());
    }

    public void addItem(UUID userId, UUID productId, int quantity) {
        // Validate product tồn tại và còn hàng
        ProductDetailDto product = productService.getById(productId);

        String key = cartKey(userId);
        String field = productId.toString();

        // Nếu đã có trong cart → cộng thêm
        String existing = (String) redisTemplate.opsForHash().get(key, field);
        int currentQty = existing != null ? parseItem(existing).getQuantity() : 0;

        CartItemDto item = CartItemDto.builder()
            .productId(productId)
            .productName(product.getName())
            .image(product.getImages().get(0))
            .price(product.getPrice())
            .quantity(currentQty + quantity)
            .build();

        redisTemplate.opsForHash().put(key, field, toJson(item));
        redisTemplate.expire(key, CART_TTL);  // reset TTL mỗi khi có activity
    }

    public void updateQuantity(UUID userId, UUID productId, int quantity) {
        if (quantity <= 0) {
            removeItem(userId, productId);
            return;
        }
        String key  = cartKey(userId);
        String existing = (String) redisTemplate.opsForHash().get(key, productId.toString());
        if (existing == null) throw new CartItemNotFoundException("Item not in cart");

        CartItemDto item = parseItem(existing);
        item.setQuantity(quantity);
        redisTemplate.opsForHash().put(key, productId.toString(), toJson(item));
        redisTemplate.expire(key, CART_TTL);
    }

    public void removeItem(UUID userId, UUID productId) {
        redisTemplate.opsForHash().delete(cartKey(userId), productId.toString());
    }

    public void clearCart(UUID userId) {
        redisTemplate.delete(cartKey(userId));
    }
}
```

### 2.4 API Endpoints

```
POST /api/v1/auth/register
POST /api/v1/auth/login
POST /api/v1/auth/refresh
POST /api/v1/auth/logout

GET    /api/v1/cart              # [AUTH required]
POST   /api/v1/cart/items        # thêm item
PUT    /api/v1/cart/items/{id}   # update quantity
DELETE /api/v1/cart/items/{id}   # xóa item
DELETE /api/v1/cart              # clear cart
```

### 2.5 Checklist hoàn thành Phase 2

```
□ Đăng ký tài khoản mới thành công
□ Login nhận được JWT
□ Gọi /api/v1/cart với JWT → trả cart (ban đầu empty)
□ Thêm sản phẩm vào cart
□ Xem redis-cli: HGETALL cart:{userId} → thấy item
□ Cart vẫn còn sau khi đăng nhập lại (TTL 7 ngày)
□ Token expire → refresh → tiếp tục được
□ Logout → token bị blacklist
□ Gọi API không có JWT → 401
```

---

## Phase 3 — Order & Payment

> **Deliverable:** Checkout từ giỏ hàng → đặt hàng → "thanh toán" (mock) → nhận email confirm.  
> Đây là nơi Kafka, Saga, và Idempotency đều được dùng.

**Thời gian ước tính:** 5–7 ngày

### 3.1 Database Schema

```sql
-- V4__orders.sql
CREATE TABLE orders (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_number        VARCHAR(30) UNIQUE NOT NULL,  -- ORD-20240115-001234
    user_id             UUID NOT NULL REFERENCES users(id),
    status              VARCHAR(30) NOT NULL DEFAULT 'PENDING_PAYMENT',
    subtotal            NUMERIC(15, 2) NOT NULL,
    shipping_fee        NUMERIC(15, 2) NOT NULL DEFAULT 0,
    discount            NUMERIC(15, 2) NOT NULL DEFAULT 0,
    total               NUMERIC(15, 2) NOT NULL,
    shipping_address    JSONB NOT NULL,   -- snapshot address lúc đặt hàng
    idempotency_key     VARCHAR(100) UNIQUE,
    notes               TEXT,
    created_at          TIMESTAMP NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP NOT NULL DEFAULT now()
);

/*
Order status flow:
PENDING_PAYMENT → PAID → PROCESSING → SHIPPED → DELIVERED
                ↘ CANCELLED (bởi user hoặc timeout)
                          ↘ REFUNDED
*/

CREATE TABLE order_items (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id    UUID NOT NULL REFERENCES orders(id),
    product_id  UUID NOT NULL REFERENCES products(id),
    product_name VARCHAR(255) NOT NULL,    -- snapshot tên lúc đặt
    product_image VARCHAR(500),
    quantity    INT NOT NULL,
    unit_price  NUMERIC(15, 2) NOT NULL,   -- snapshot giá lúc đặt
    subtotal    NUMERIC(15, 2) NOT NULL
);

CREATE TABLE payments (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id            UUID NOT NULL REFERENCES orders(id),
    amount              NUMERIC(15, 2) NOT NULL,
    method              VARCHAR(30) NOT NULL,   -- VNPAY, MOMO, COD, BANK_TRANSFER
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    gateway_transaction_id VARCHAR(100),
    idempotency_key     VARCHAR(100) UNIQUE,
    metadata            JSONB,
    created_at          TIMESTAMP NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_orders_user   ON orders(user_id);
CREATE INDEX idx_orders_status ON orders(status);
CREATE INDEX idx_orders_number ON orders(order_number);
```

### 3.2 Order Service — Saga Flow

```
Checkout flow:

1. POST /api/v1/orders/checkout
   ├── Validate cart không empty
   ├── Validate sản phẩm còn hàng (check inventory)
   ├── Tạo Order record (status=PENDING_PAYMENT)
   ├── Reserve inventory (trừ reserved, chưa trừ quantity)
   ├── afterCommit() → publish OrderCreatedEvent vào Kafka
   └── Trả về orderId + payment URL

2. POST /api/v1/payments/confirm (mock)
   ├── Validate payment
   ├── Update Order status → PAID
   ├── afterCommit() → publish OrderPaidEvent
   └── Trả về success

Kafka consumers (async):
   OrderPaidEvent → InventoryService: trừ quantity thật sự
   OrderPaidEvent → EmailService: gửi email confirm
   OrderPaidEvent → AuditService: ghi audit log

Timeout job (Scheduler, mỗi 5 phút):
   Orders PENDING_PAYMENT quá 30 phút → CANCELLED
   → publish OrderCancelledEvent
   → InventoryService: release reservation
```

```java
@Service @RequiredArgsConstructor @Slf4j
public class OrderService {

    private final OrderRepository orderRepo;
    private final InventoryRepository inventoryRepo;
    private final CartService cartService;
    private final OrderEventProducer eventProducer;

    @Transactional
    public CreateOrderResponse checkout(UUID userId, CheckoutRequest request) {

        // 1. Idempotency
        if (request.getIdempotencyKey() != null) {
            orderRepo.findByIdempotencyKey(request.getIdempotencyKey())
                .ifPresent(existing -> {
                    throw new DuplicateOrderException(existing.getId().toString());
                });
        }

        // 2. Lấy cart
        CartDto cart = cartService.getCart(userId);
        if (cart.getItems().isEmpty())
            throw new EmptyCartException("Giỏ hàng trống");

        // 3. Validate và reserve inventory — lock theo thứ tự ID để tránh deadlock
        List<UUID> productIds = cart.getItems().stream()
            .map(CartItemDto::getProductId)
            .sorted()   // sort để tránh deadlock giữa concurrent requests
            .toList();

        Map<UUID, Inventory> inventoryMap = new HashMap<>();
        for (UUID pid : productIds) {
            Inventory inv = inventoryRepo.findByProductIdWithLock(pid)
                .orElseThrow(() -> new ProductNotFoundException("Product: " + pid));

            CartItemDto item = cart.getItem(pid);
            int available = inv.getQuantity() - inv.getReserved();
            if (available < item.getQuantity())
                throw new InsufficientInventoryException(
                    "Sản phẩm " + item.getProductName() + " không đủ hàng");

            // Reserve
            inv.setReserved(inv.getReserved() + item.getQuantity());
            inventoryMap.put(pid, inv);
        }
        inventoryRepo.saveAll(inventoryMap.values());

        // 4. Tạo order
        Order order = buildOrder(userId, cart, request);
        orderRepo.save(order);

        // 5. Xóa cart sau khi đặt hàng
        cartService.clearCart(userId);

        // 6. Publish event AFTER DB commit
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    eventProducer.publishOrderCreated(order);
                }
            }
        );

        log.info("Order created: {} for user: {}", order.getOrderNumber(), userId);
        return new CreateOrderResponse(order.getId(), order.getOrderNumber(),
            order.getTotal(), generatePaymentUrl(order));
    }

    private Order buildOrder(UUID userId, CartDto cart, CheckoutRequest req) {
        Order order = new Order();
        order.setOrderNumber(generateOrderNumber());
        order.setUserId(userId);
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        order.setIdempotencyKey(req.getIdempotencyKey());
        order.setShippingAddress(objectMapper.valueToTree(req.getShippingAddress()));

        List<OrderItem> items = cart.getItems().stream().map(ci -> {
            OrderItem item = new OrderItem();
            item.setOrder(order);
            item.setProductId(ci.getProductId());
            item.setProductName(ci.getProductName());  // snapshot
            item.setQuantity(ci.getQuantity());
            item.setUnitPrice(ci.getPrice());            // snapshot giá lúc đặt
            item.setSubtotal(ci.getPrice().multiply(BigDecimal.valueOf(ci.getQuantity())));
            return item;
        }).toList();

        order.setItems(items);
        order.setSubtotal(cart.getTotal());
        order.setShippingFee(calculateShippingFee(cart));
        order.setTotal(order.getSubtotal().add(order.getShippingFee()));
        return order;
    }

    private String generateOrderNumber() {
        return "ORD-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
            + "-" + String.format("%06d", new Random().nextInt(999999));
    }
}
```

### 3.3 Kafka Topics & Consumers

```
Topics:
  ecommerce.order.events       # OrderCreated, OrderPaid, OrderCancelled
  ecommerce.inventory.commands # ReserveInventory, ReleaseInventory, DeductInventory
  ecommerce.email.send         # OrderConfirmEmail, ShipmentEmail
  ecommerce.order.events.dlq   # Dead letter queue

Consumers:
  InventoryConsumer   (group: inventory-service)
  EmailConsumer       (group: email-service)
  AuditConsumer       (group: audit-service)
```

```java
@Component @Slf4j
public class InventoryConsumer {

    private final InventoryRepository inventoryRepo;

    @KafkaListener(topics = "ecommerce.order.events", groupId = "inventory-service")
    @Transactional
    public void handle(@Payload OrderEvent event, Acknowledgment ack) {
        try {
            switch (event.getEventType()) {
                case "ORDER_PAID" -> deductInventory(event);
                case "ORDER_CANCELLED" -> releaseReservation(event);
            }
            ack.acknowledge();
        } catch (Exception e) {
            log.error("InventoryConsumer failed for order={}: {}", event.getOrderId(), e.getMessage());
            // Không ack → retry → sau max retry → DLQ
        }
    }

    private void deductInventory(OrderEvent event) {
        event.getItems().forEach(item -> {
            Inventory inv = inventoryRepo.findByProductIdWithLock(item.getProductId())
                .orElseThrow(() -> new RuntimeException("Inventory not found"));

            // Trừ thật sự sau khi đã paid
            inv.setQuantity(inv.getQuantity() - item.getQuantity());
            inv.setReserved(inv.getReserved() - item.getQuantity());
            inventoryRepo.save(inv);
        });
    }
}
```

### 3.4 Order Timeout Scheduler

```java
@Component @RequiredArgsConstructor @Slf4j
public class OrderTimeoutScheduler {

    private final OrderRepository orderRepo;
    private final OrderEventProducer eventProducer;

    // Chạy mỗi 5 phút
    @Scheduled(fixedDelay = 300_000)
    @Transactional
    public void cancelExpiredOrders() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(30);

        List<Order> expired = orderRepo
            .findByStatusAndCreatedAtBefore(OrderStatus.PENDING_PAYMENT, cutoff);

        expired.forEach(order -> {
            order.setStatus(OrderStatus.CANCELLED);
            orderRepo.save(order);
            eventProducer.publishOrderCancelled(order, "Payment timeout");
            log.info("Order cancelled (timeout): {}", order.getOrderNumber());
        });

        if (!expired.isEmpty())
            log.info("Cancelled {} expired orders", expired.size());
    }
}
```

### 3.5 Checklist hoàn thành Phase 3

```
□ POST /api/v1/orders/checkout → tạo order thành công
□ Inventory reserved tăng lên trong DB
□ Kafka UI: ecommerce.order.events có message
□ POST /api/v1/payments/confirm → order status = PAID
□ Sau confirm: inventory quantity giảm, reserved giảm
□ Email consumer log ra "Gửi email confirm đến..."
□ Gửi cùng request 2 lần (idempotency key giống nhau) → trả kết quả cũ, 1 order
□ Order PENDING_PAYMENT quá 30 phút → tự CANCELLED
□ GET /api/v1/orders → list orders của user
□ GET /api/v1/orders/{id} → detail kèm items
```

---

## Phase 4 — Flash Sale & Inventory Control

> **Deliverable:** 10.000 user cùng click mua, chỉ 100 sản phẩm — đúng 100 người mua được, 9.900 người nhận thông báo hết hàng. Không oversell, không race condition.

**Thời gian ước tính:** 4–5 ngày

### 4.1 Vấn đề Flash Sale

```
Naive approach (BUG):
Thread 1: SELECT quantity=100, reserved=0 → available=100 ✓
Thread 2: SELECT quantity=100, reserved=0 → available=100 ✓
Thread 1: UPDATE reserved=1
Thread 2: UPDATE reserved=1   ← LẼ RA phải là 2, nhưng lại là 1!
→ Oversell

Solution: Redis atomic counter + Kafka queue
```

### 4.2 Flash Sale Design

```
Trước flash sale (admin setup):
  redis.set("flash_sale:{saleId}:stock", 100)
  redis.set("flash_sale:{saleId}:status", "ACTIVE")
  redis.set("flash_sale:{saleId}:end_time", epoch)

Khi user click "Mua ngay":
  1. Kiểm tra login (JWT)
  2. Kiểm tra user chưa mua (redis: SET flash_sale:{saleId}:buyer:{userId} NX)
  3. DECR flash_sale:{saleId}:stock
     - Nếu kết quả >= 0 → vào queue Kafka
     - Nếu kết quả < 0  → INCR lại (rollback) → trả "Hết hàng"
  4. Kafka consumer → tạo order thật sự → gửi email
  5. Trả về "Đặt hàng thành công, đang xử lý"

Key insight: Redis DECR là atomic — không cần lock, cực nhanh (~0.1ms)
```

```java
@Service @RequiredArgsConstructor @Slf4j
public class FlashSaleService {

    private final RedisTemplate<String, String> redisTemplate;
    private final FlashSaleEventProducer eventProducer;

    public FlashSaleResult purchase(UUID userId, UUID saleId) {

        String stockKey      = "flash_sale:" + saleId + ":stock";
        String statusKey     = "flash_sale:" + saleId + ":status";
        String buyerKey      = "flash_sale:" + saleId + ":buyer:" + userId;
        String endTimeKey    = "flash_sale:" + saleId + ":end_time";

        // 1. Check sale còn active
        String status = redisTemplate.opsForValue().get(statusKey);
        if (!"ACTIVE".equals(status))
            return FlashSaleResult.saleNotActive();

        // 2. Check thời gian
        String endTimeStr = redisTemplate.opsForValue().get(endTimeKey);
        if (endTimeStr != null && Instant.now().isAfter(Instant.ofEpochSecond(Long.parseLong(endTimeStr))))
            return FlashSaleResult.saleEnded();

        // 3. Mỗi user chỉ mua 1 lần
        Boolean isNewBuyer = redisTemplate.opsForValue()
            .setIfAbsent(buyerKey, "1", Duration.ofDays(1));
        if (!Boolean.TRUE.equals(isNewBuyer))
            return FlashSaleResult.alreadyPurchased();

        // 4. Atomic decrement — đây là bước quan trọng nhất
        Long remaining = redisTemplate.opsForValue().decrement(stockKey);

        if (remaining == null || remaining < 0) {
            // Rollback — hoàn lại stock và xóa buyer record
            redisTemplate.opsForValue().increment(stockKey);
            redisTemplate.delete(buyerKey);
            return FlashSaleResult.soldOut();
        }

        // 5. Vào queue xử lý — không tạo order ngay (tránh DB spike)
        eventProducer.publishFlashSalePurchase(FlashSaleEvent.builder()
            .userId(userId)
            .saleId(saleId)
            .remainingStock(remaining)
            .requestedAt(Instant.now())
            .build());

        log.info("Flash sale purchase queued: user={} sale={} remaining={}",
            userId, saleId, remaining);

        return FlashSaleResult.queued("Đặt hàng thành công! Đang xử lý...");
    }
}
```

### 4.3 Elasticsearch — Product Search

Thay thế ILIKE search Phase 1 bằng Elasticsearch khi sản phẩm nhiều (>10.000):

```java
@Document(indexName = "products")
@Data
public class ProductDocument {
    @Id private String id;

    @Field(type = FieldType.Text, analyzer = "standard")
    private String name;

    @Field(type = FieldType.Text, analyzer = "standard")
    private String description;

    @Field(type = FieldType.Keyword)
    private String categorySlug;

    @Field(type = FieldType.Double)
    private double price;

    @Field(type = FieldType.Keyword)
    private String status;
}

public interface ProductSearchRepository
    extends ElasticsearchRepository<ProductDocument, String> {

    @Query("""
        {
          "multi_match": {
            "query": "?0",
            "fields": ["name^3", "description"],
            "fuzziness": "AUTO"
          }
        }
        """)
    Page<ProductDocument> search(String query, Pageable pageable);
}
```

**Sync DB → Elasticsearch:** Khi product được tạo/update → publish Kafka event → ES consumer index document. Không sync trực tiếp trong request.

### 4.4 Checklist hoàn thành Phase 4

```
□ Tạo flash sale: stock=10, chạy script 50 concurrent requests
□ Đúng 10 người nhận "thành công", 40 người nhận "hết hàng"
□ Redis: GET flash_sale:{id}:stock = 0 (không âm)
□ Không có duplicate order cho cùng 1 userId
□ Elasticsearch: search "iphone" trả kết quả có fuzzy matching
□ Thêm product mới → Kafka → ES indexed trong vòng 1-2 giây
□ k6 flash sale test: 1000 VU cùng click, stock=100 → đúng 100 orders tạo ra
```

---

## Phase 5 — Scale, Monitor & Production-Ready

> **Deliverable:** Load test 1000+ TPS pass. Dashboard Grafana hiển thị P99 < 200ms. Alert rules hoạt động.

**Thời gian ước tính:** 4–5 ngày

### 5.1 Custom Metrics

```java
// Đo mọi thứ quan trọng về business
@PostConstruct void initMetrics() {
    orderCreatedCounter   = Counter.builder("ecommerce.order.created").register(meterRegistry);
    orderPaidCounter      = Counter.builder("ecommerce.order.paid").register(meterRegistry);
    flashSaleSuccessCount = Counter.builder("ecommerce.flash_sale.success").register(meterRegistry);
    flashSaleSoldOut      = Counter.builder("ecommerce.flash_sale.sold_out").register(meterRegistry);
    checkoutTimer         = Timer.builder("ecommerce.checkout.duration")
                                .publishPercentiles(0.5, 0.95, 0.99)
                                .register(meterRegistry);
    cartValueSummary      = DistributionSummary.builder("ecommerce.cart.value")
                                .baseUnit("VND").register(meterRegistry);
}
```

### 5.2 Grafana Dashboard — PromQL queries

```promql
# TPS toàn hệ thống
sum(rate(http_server_requests_seconds_count[1m]))

# P99 Latency checkout
histogram_quantile(0.99,
  rate(ecommerce_checkout_duration_seconds_bucket[1m])) * 1000

# Order conversion rate
rate(ecommerce_order_paid_total[5m]) / rate(ecommerce_order_created_total[5m])

# Flash sale stock remaining (realtime)
# → Đọc từ Redis qua custom metric endpoint

# Kafka consumer lag
kafka_consumer_group_lag{group="inventory-service"}

# DB connection pool
hikaricp_connections_pending{pool="EcommercePool"}
hikaricp_connections_active{pool="EcommercePool"}

# Cache hit rate
rate(redis_commands_duration_seconds_count{command="get"}[1m])
```

### 5.3 k6 — Full Load Test Suite

```javascript
// k6/load-test.js — kịch bản thực tế
export const options = {
    scenarios: {
        // Browse sản phẩm — traffic cao nhất
        browsing: {
            executor: 'constant-arrival-rate',
            rate: 500,          // 500 req/s
            timeUnit: '1s',
            duration: '5m',
            preAllocatedVUs: 100,
        },
        // Checkout — ít hơn nhưng nặng hơn
        checkout: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: '1m', target: 50 },
                { duration: '3m', target: 50 },
                { duration: '1m', target: 0  },
            ],
        },
        // Flash sale spike — đột biến ngắn
        flash_sale_spike: {
            executor: 'ramping-arrival-rate',
            startRate: 0,
            timeUnit: '1s',
            stages: [
                { duration: '10s', target: 1000 },  // ramp lên 1000 req/s
                { duration: '30s', target: 1000 },
                { duration: '10s', target: 0    },
            ],
            preAllocatedVUs: 500,
            startTime: '5m',    // bắt đầu sau 5 phút
        },
    },
    thresholds: {
        'http_req_duration{scenario:browsing}':   ['p(99)<100'],
        'http_req_duration{scenario:checkout}':   ['p(99)<500'],
        'http_req_duration{scenario:flash_sale_spike}': ['p(99)<1000'],
        'http_req_failed':                        ['rate<0.01'],
    },
};
```

### 5.4 Bottleneck Checklist

Kiểm tra theo thứ tự trước khi kết luận cần thêm server:

```
□ Missing DB index?
  → EXPLAIN ANALYZE query chậm
  → Thêm index phù hợp

□ N+1 query?
  → spring.jpa.show-sql=true + đếm số queries cho 1 request
  → Dùng JOIN FETCH hoặc @BatchSize

□ HikariCP pool quá nhỏ?
  → hikaricp_connections_pending > 0 liên tục
  → Tăng maximum-pool-size (theo công thức)

□ Cache miss quá nhiều?
  → Xem Redis keyspace stats
  → Tăng TTL hoặc warm up cache khi start

□ Kafka consumer lag tăng?
  → Tăng số partition + consumer instances

□ JVM GC pause?
  → jvm_gc_pause_seconds cao → tune GC settings
  → Xem xét tăng heap size
```

### 5.5 Alert Rules

```yaml
# monitoring/alerts.yml
groups:
  - name: ecommerce-critical
    rules:
      - alert: HighCheckoutLatency
        expr: histogram_quantile(0.99,
                rate(ecommerce_checkout_duration_seconds_bucket[2m])) > 1
        for: 3m
        annotations:
          summary: "Checkout P99 > 1s"

      - alert: OrderFailureSpike
        expr: rate(ecommerce_order_failed_total[5m]) > 0.05
        for: 2m
        annotations:
          summary: "Order failure rate > 5%"

      - alert: InventoryOversell
        expr: ecommerce_inventory_quantity < 0
        for: 0m   # alert ngay lập tức
        labels:
          severity: critical
        annotations:
          summary: "CRITICAL: Inventory went negative — possible oversell"

      - alert: KafkaConsumerLagHigh
        expr: kafka_consumer_group_lag{group=~".*-service"} > 1000
        for: 5m
        annotations:
          summary: "Kafka consumer {{ $labels.group }} lag > 1000"
```

### 5.6 Checklist hoàn thành Phase 5

```
□ k6 browsing test 500 req/s × 5 phút → P99 < 100ms
□ k6 checkout test 50 VU → P99 < 500ms, error < 1%
□ k6 flash sale spike 1000 req/s × 30s → 0 oversell
□ Grafana dashboard hiển thị đủ 6 panels
□ Alert test: kill DB → alert "DB connection failed" trong vòng 1 phút
□ Alert test: chạy script tạo lỗi → alert "Order failure spike"
□ Restart app → metrics tiếp tục (không mất data trong Prometheus)
□ Log có đủ correlation ID để trace 1 request qua hệ thống
```

---

## Timeline tổng quan

```
Tuần 1–2   Phase 0 + Phase 1   Infra + Product catalog
Tuần 3–4   Phase 2             Auth + Cart
Tuần 5–7   Phase 3             Order + Payment + Kafka
Tuần 8–9   Phase 4             Flash sale + Elasticsearch
Tuần 10–11 Phase 5             Load test + Monitor + Optimize
Tuần 12    Buffer              Fix bug, refactor, documentation
```

---

## Những điểm dễ sai — ghi nhớ kỹ

| Tình huống | Sai thường gặp | Cách đúng |
|---|---|---|
| Lưu giá trong order | Lấy giá từ product lúc xem | Snapshot giá vào order_items.unit_price lúc đặt |
| Cart | Lưu vào PostgreSQL | Lưu vào Redis — đọc/ghi liên tục, không cần ACID |
| Flash sale | Check tồn kho trong DB | Redis DECR — atomic, 0.1ms, không lock |
| Payment | Xử lý 1 lần | Idempotency key bắt buộc — gateway có thể callback 2 lần |
| Inventory | Trừ ngay khi đặt | Reserve khi đặt, deduct khi paid |
| Session | Lưu trên server | JWT stateless — server không nhớ gì |
| Search | ILIKE query | Elasticsearch cho full-text |
| Email | Gửi trong request | Async qua Kafka — email gateway chậm không ảnh hưởng UX |
| Order number | UUID | Human-readable: ORD-20240115-001234 |
| Địa chỉ giao hàng | FK vào addresses table | Snapshot JSON vào orders.shipping_address |
