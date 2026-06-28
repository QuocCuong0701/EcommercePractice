# Phase 3 — Order & Payment (Hoàn chỉnh)

> **Deliverable:** Checkout từ giỏ hàng → đặt hàng → "thanh toán" (mock) → nhận email confirm.  
> Đây là nơi Kafka, Saga, và Idempotency đều được dùng.

**Thời gian ước tính:** 5–7 ngày

---

## Mục lục

- [1. Saga Flow](#1-saga-flow)
- [2. Database Schema (Flyway)](#2-database-schema-flyway)
- [3. Enums](#3-enums)
- [4. Entities](#4-entities)
- [5. Repositories](#5-repositories)
- [6. DTOs & Events](#6-dtos--events)
- [7. Producers (Kafka)](#7-producers-kafka)
- [8. Services](#8-services)
- [9. Controllers](#9-controllers)
- [10. Consumers (Kafka)](#10-consumers-kafka)
- [11. Exceptions](#11-exceptions)
- [12. Frontend](#12-frontend)
- [13. Application.yml bổ sung](#13-applicationyml-bổ-sung)
- [14. Checklist hoàn thành](#14-checklist-hoàn-thành)
- [15. Thứ tự implement](#15-thứ-tự-implement)

---

## 1. Saga Flow

```
Checkout flow:

1. POST /api/v1/orders/checkout
   ├── Idempotency check (idempotency_key → duplicate? throw)
   ├── Validate cart không empty
   ├── Validate sản phẩm còn hàng (check inventory, PESSIMISTIC_WRITE lock)
   ├── Reserve inventory (trừ reserved, chưa trừ quantity)
   ├── Tạo Order record (status=PENDING_PAYMENT)
   ├── Xóa cart
   ├── afterCommit() → publish OrderCreatedEvent vào Kafka
   └── Trả về orderId + payment URL

2. POST /api/v1/payments/confirm (mock)
   ├── Idempotency check
   ├── Validate order tồn tại và status = PENDING_PAYMENT
   ├── Tạo Payment record (status=SUCCESS)
   ├── Update Order status → PAID
   ├── afterCommit() → publish OrderPaidEvent
   └── Trả về success

Kafka consumers (async):
   OrderPaidEvent → InventoryConsumer: deductInventory (trừ quantity thật sự)
   OrderPaidEvent → EmailConsumer: gửi email confirm
   OrderPaidEvent → AuditConsumer: ghi audit log

Timeout job (Scheduler, mỗi 5 phút):
   Orders PENDING_PAYMENT quá 30 phút → CANCELLED
   → publish OrderCancelledEvent
   → InventoryConsumer: releaseReservation
```

---

## 2. Database Schema (Flyway)

### V4__orders.sql

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

### V5__order_indexes.sql

```sql
-- V5__order_indexes.sql
CREATE INDEX idx_payments_order ON payments(order_id);
CREATE INDEX idx_payments_idempotency ON payments(idempotency_key);
CREATE INDEX idx_order_items_order ON order_items(order_id);
CREATE INDEX idx_orders_created ON orders(created_at);
```

---

## 3. Enums

### OrderStatus.java

```java
package com.ecommerce.domain.order;

public enum OrderStatus {
    PENDING_PAYMENT,
    PAID,
    PROCESSING,
    SHIPPED,
    DELIVERED,
    CANCELLED,
    REFUNDED
}
```

### PaymentStatus.java

```java
package com.ecommerce.domain.payment;

public enum PaymentStatus {
    PENDING,
    SUCCESS,
    FAILED,
    REFUNDED
}
```

### PaymentMethod.java

```java
package com.ecommerce.domain.payment;

public enum PaymentMethod {
    VNPAY,
    MOMO,
    COD,
    BANK_TRANSFER
}
```

---

## 4. Entities

### Order.java

```java
package com.ecommerce.domain.order;

@Entity
@Table(name = "orders")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "order_number", unique = true, nullable = false, length = 30)
    private String orderNumber;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OrderStatus status = OrderStatus.PENDING_PAYMENT;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal subtotal;

    @Column(name = "shipping_fee", precision = 15, scale = 2)
    private BigDecimal shippingFee = BigDecimal.ZERO;

    @Column(precision = 15, scale = 2)
    private BigDecimal discount = BigDecimal.ZERO;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal total;

    @Column(name = "shipping_address", columnDefinition = "jsonb", nullable = false)
    private JsonNode shippingAddress;

    @Column(name = "idempotency_key", unique = true, length = 100)
    private String idempotencyKey;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<OrderItem> items = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
```

### OrderItem.java

```java
package com.ecommerce.domain.order;

@Entity
@Table(name = "order_items")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "product_name", nullable = false, length = 255)
    private String productName;

    @Column(name = "product_image", length = 500)
    private String productImage;

    @Column(nullable = false)
    private Integer quantity;

    @Column(name = "unit_price", nullable = false, precision = 15, scale = 2)
    private BigDecimal unitPrice;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal subtotal;
}
```

### Payment.java

```java
package com.ecommerce.domain.payment;

@Entity
@Table(name = "payments")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PaymentMethod method;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status = PaymentStatus.PENDING;

    @Column(name = "gateway_transaction_id", length = 100)
    private String gatewayTransactionId;

    @Column(name = "idempotency_key", unique = true, length = 100)
    private String idempotencyKey;

    @Column(columnDefinition = "jsonb")
    private JsonNode metadata;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
```

---

## 5. Repositories

### OrderRepository.java

```java
package com.ecommerce.domain.order;

@Repository
public interface OrderRepository extends JpaRepository<Order, UUID> {

    Optional<Order> findByIdempotencyKey(String idempotencyKey);

    Optional<Order> findByOrderNumber(String orderNumber);

    List<Order> findByStatusAndCreatedAtBefore(OrderStatus status, LocalDateTime createdAt);

    Page<Order> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    @Query("SELECT o FROM Order o LEFT JOIN FETCH o.items WHERE o.id = :id")
    Optional<Order> findByIdWithItems(@Param("id") UUID id);
}
```

### PaymentRepository.java

```java
package com.ecommerce.domain.payment;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByOrderId(UUID orderId);

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    List<Payment> findByOrderIdOrderByCreatedAtDesc(UUID orderId);
}
```

### InventoryRepository.java

```java
package com.ecommerce.domain.inventory;

@Repository
public interface InventoryRepository extends JpaRepository<Inventory, UUID> {

    Optional<Inventory> findByProductId(UUID productId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM Inventory i WHERE i.product.id = :productId")
    Optional<Inventory> findByProductIdWithLock(@Param("productId") UUID productId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM Inventory i WHERE i.product.id IN :productIds")
    List<Inventory> findAllByProductIdInWithLock(@Param("productIds") List<UUID> productIds);
}
```

---

## 6. DTOs & Events

### CheckoutRequest.java

```java
package com.ecommerce.dto;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CheckoutRequest {
    private String idempotencyKey;
    private ShippingAddressDto shippingAddress;
    private String notes;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ShippingAddressDto {
        private String fullName;
        private String phone;
        private String address;
        private String province;
        private String district;
    }
}
```

### CreateOrderResponse.java

```java
package com.ecommerce.dto;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateOrderResponse {
    private UUID orderId;
    private String orderNumber;
    private BigDecimal total;
    private String paymentUrl;
    private String status;
}
```

### OrderSummaryDto.java

```java
package com.ecommerce.dto;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderSummaryDto {
    private UUID id;
    private String orderNumber;
    private String status;
    private BigDecimal total;
    private Integer itemCount;
    private LocalDateTime createdAt;

    public static OrderSummaryDto from(Order order) {
        return OrderSummaryDto.builder()
            .id(order.getId())
            .orderNumber(order.getOrderNumber())
            .status(order.getStatus().name())
            .total(order.getTotal())
            .itemCount(order.getItems() != null ? order.getItems().size() : 0)
            .createdAt(order.getCreatedAt())
            .build();
    }
}
```

### OrderDetailDto.java

```java
package com.ecommerce.dto;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderDetailDto {
    private UUID id;
    private String orderNumber;
    private String status;
    private BigDecimal subtotal;
    private BigDecimal shippingFee;
    private BigDecimal discount;
    private BigDecimal total;
    private String notes;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<OrderItemDto> items;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class OrderItemDto {
        private UUID productId;
        private String productName;
        private String productImage;
        private Integer quantity;
        private BigDecimal unitPrice;
        private BigDecimal subtotal;
    }

    public static OrderDetailDto from(Order order) {
        return OrderDetailDto.builder()
            .id(order.getId())
            .orderNumber(order.getOrderNumber())
            .status(order.getStatus().name())
            .subtotal(order.getSubtotal())
            .shippingFee(order.getShippingFee())
            .discount(order.getDiscount())
            .total(order.getTotal())
            .notes(order.getNotes())
            .createdAt(order.getCreatedAt())
            .updatedAt(order.getUpdatedAt())
            .items(order.getItems().stream()
                .map(i -> OrderItemDto.builder()
                    .productId(i.getProductId())
                    .productName(i.getProductName())
                    .productImage(i.getProductImage())
                    .quantity(i.getQuantity())
                    .unitPrice(i.getUnitPrice())
                    .subtotal(i.getSubtotal())
                    .build())
                .toList())
            .build();
    }
}
```

### PaymentConfirmRequest.java

```java
package com.ecommerce.dto;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentConfirmRequest {
    @NotNull
    private UUID orderId;

    @NotNull
    private PaymentMethod paymentMethod;

    private String idempotencyKey;
}
```

### PaymentConfirmResponse.java

```java
package com.ecommerce.dto;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentConfirmResponse {
    private boolean success;
    private UUID paymentId;
    private String transactionId;
    private String status;
    private String message;
}
```

### OrderEvent.java (Kafka event payload)

```java
package com.ecommerce.event;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderEvent {
    private String eventType;          // ORDER_CREATED, ORDER_PAID, ORDER_CANCELLED
    private UUID orderId;
    private String orderNumber;
    private UUID userId;
    private BigDecimal total;
    private String reason;             // cho CANCELLED
    private List<OrderItemEvent> items;
    private Instant timestamp;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class OrderItemEvent {
        private UUID productId;
        private String productName;
        private Integer quantity;
        private BigDecimal unitPrice;
    }
}
```

---

## 7. Producers (Kafka)

### OrderEventProducer.java

```java
package com.ecommerce.event;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrderEventProducer {

    // KafkaTemplate dùng để gửi message, generic <String, OrderEvent>:
    //   - Key:   String — orderId, dùng để partition (cùng order → cùng partition, giữ đúng thứ tự)
    //   - Value: OrderEvent — nội dung event
    private final KafkaTemplate<String, OrderEvent> kafkaTemplate;

    // Topic duy nhất "ecommerce.order.events" — các consumer khác nhau (inventory, email, audit)
    // cùng đọc từ topic này nhưng khác groupId, nên mỗi consumer đều nhận được bản copy của message.
    // Dùng 1 topic → đơn giản, dễ maintain, không cần tạo nhiều topic riêng.
    private static final String TOPIC = "ecommerce.order.events";

    // Gửi event khi order vừa được tạo (status = PENDING_PAYMENT)
    // Consumer: EmailConsumer (gửi email xác nhận đơn hàng)
    public void publishOrderCreated(Order order) {
        OrderEvent event = buildEvent("ORDER_CREATED", order, null);

        // send() trả về ListenableFuture — dùng whenComplete() để log async, không block thread.
        // Key = order.getId() — tất cả message cùng 1 order sẽ vào cùng partition, giữ đúng thứ tự.
        // RecordMetadata.offset — vị trí message trong partition, dùng để debug.
        kafkaTemplate.send(TOPIC, order.getId().toString(), event)
            .whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Failed to publish ORDER_CREATED for order={}: {}", order.getId(), ex.getMessage());
                } else {
                    log.info("ORDER_CREATED published: order={} offset={}", order.getId(), result.getRecordMetadata().offset());
                }
            });
    }

    // Gửi event sau khi payment được xác nhận (status = PAID)
    // Consumer: InventoryConsumer (trừ inventory thật) + EmailConsumer (gửi email xác nhận thanh toán) + AuditConsumer
    public void publishOrderPaid(Order order) {
        OrderEvent event = buildEvent("ORDER_PAID", order, null);
        kafkaTemplate.send(TOPIC, order.getId().toString(), event);
    }

    // Gửi event khi order bị hủy (timeout hoặc user hủy)
    // Consumer: InventoryConsumer (release reservation) + EmailConsumer + AuditConsumer
    // reason: lý do hủy ("Payment timeout" từ scheduler, hoặc "Cancelled by user")
    public void publishOrderCancelled(Order order, String reason) {
        OrderEvent event = buildEvent("ORDER_CANCELLED", order, reason);
        kafkaTemplate.send(TOPIC, order.getId().toString(), event);
    }

    // Factory method — tạo OrderEvent từ Order entity + eventType.
    // Lý do snapshot (copy dữ liệu vào event thay vì gửi reference):
    //   - Order có thể thay đổi sau khi event được publish (consumer xử lý async)
    //   - Snapshot đảm bảo consumer luôn thấy dữ liệu đúng tại thời điểm event xảy ra
    // items: chỉ copy các trường cần thiết (productId, productName, quantity, unitPrice)
    //   - Không gửi toàn bộ entity (tránh serialize không cần thiết)
    private OrderEvent buildEvent(String eventType, Order order, String reason) {
        return OrderEvent.builder()
            .eventType(eventType)
            .orderId(order.getId())
            .orderNumber(order.getOrderNumber())
            .userId(order.getUserId())
            .total(order.getTotal())
            .reason(reason)
            .items(order.getItems().stream()
                .map(i -> OrderEvent.OrderItemEvent.builder()
                    .productId(i.getProductId())
                    .productName(i.getProductName())
                    .quantity(i.getQuantity())
                    .unitPrice(i.getUnitPrice())
                    .build())
                .toList())
            .timestamp(Instant.now())
            .build();
    }
}
```

### PaymentEventProducer.java

```java
package com.ecommerce.event;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentEventProducer {

    // Cùng KafkaTemplate và TOPIC với OrderEventProducer — vì đều là order events.
    // Tách riêng class để phân tách trách nhiệm:
    //   - OrderEventProducer: gửi event từ OrderService (tạo/hủy order)
    //   - PaymentEventProducer: gửi event từ PaymentService (kết quả thanh toán)
    // Dù cùng TOPIC nhưng logic build event khác nhau, tách class giúp dễ maintain.
    private final KafkaTemplate<String, OrderEvent> kafkaTemplate;
    private static final String TOPIC = "ecommerce.order.events";

    // Gửi event khi payment được xác nhận thành công (status = SUCCESS).
    // Được gọi từ PaymentService.confirmPayment() sau khi DB commit (afterCommit).
    // eventType = "ORDER_PAID" — cùng event type với OrderEventProducer.publishOrderPaid().
    // Consumer: InventoryConsumer (deductInventory) + EmailConsumer + AuditConsumer.
    // Lý do dùng eventType "ORDER_PAID" thay vì "PAYMENT_CONFIRMED":
    //   - Consumer chỉ cần switch trên 1 event type, không cần xử lý 2 event riêng.
    //   - "ORDER_PAID" phản ánh trạng thái business (order đã paid) chứ không phải hành động kỹ thuật.
    public void publishPaymentConfirmed(Order order) {
        OrderEvent event = OrderEvent.builder()
            .eventType("ORDER_PAID")
            .orderId(order.getId())
            .orderNumber(order.getOrderNumber())
            .userId(order.getUserId())
            .total(order.getTotal())
            .items(order.getItems().stream()
                .map(i -> OrderEvent.OrderItemEvent.builder()
                    .productId(i.getProductId())
                    .productName(i.getProductName())
                    .quantity(i.getQuantity())
                    .unitPrice(i.getUnitPrice())
                    .build())
                .toList())
            .timestamp(Instant.now())
            .build();

        // Dùng whenComplete() để log lỗi async — không throw exception ra ngoài.
        // Nếu Kafka down, exception chỉ được log, không rollback transaction.
        // Lý do: transaction DB đã commit, không thể rollback để gửi lại.
        // Giải pháp cho production: Outbox Pattern (Phase 5+).
        kafkaTemplate.send(TOPIC, order.getId().toString(), event)
            .whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Failed to publish ORDER_PAID for order={}: {}", order.getId(), ex.getMessage());
                }
            });
    }

    // Gửi event khi payment thất bại.
    // Dùng cho Phase 4+ khi tích hợp gateway thật (VNPAY, MOMO).
    // eventType = "PAYMENT_FAILED" — riêng biệt, không gộp chung với ORDER_CANCELLED.
    // Lý do: payment failed có thể retry (chọn phương thức khác), không hủy order ngay.
    // Consumer: AuditConsumer (ghi log) + EmailConsumer (thông báo lỗi thanh toán).
    // Hiện tại Phase 3 mock luôn success nên method này chưa dùng đến.
    public void publishPaymentFailed(UUID orderId, String reason) {
        OrderEvent event = OrderEvent.builder()
            .eventType("PAYMENT_FAILED")
            .orderId(orderId)
            .reason(reason)
            .timestamp(Instant.now())
            .build();

        kafkaTemplate.send(TOPIC, orderId.toString(), event);
    }
}
```

---

## 8. Services

### OrderService.java

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    // OrderService: xương sống của checkout flow.
    // Chịu trách nhiệm: validate → reserve inventory → tạo order → xóa cart → publish event.
    // Lưu ý: tất cả logic nằm trong 1 transaction (@Transactional).
    // Nếu bất kỳ bước nào fail (vd: hết hàng), toàn bộ rollback — không có side effect.
    private final OrderRepository orderRepo;
    private final InventoryRepository inventoryRepo;
    private final CartService cartService;
    private final OrderEventProducer eventProducer;
    private final ObjectMapper objectMapper;

    private static final Random RANDOM = new Random();

    // === CHECKOUT: phương thức chính ===
    // @Transactional đảm bảo toàn bộ method chạy trong 1 DB transaction.
    // Ngoại lệ: publish event Kafka được tách ra sau commit (afterCommit).
    //   — Lý do: nếu event publish fail, không rollback DB (transaction đã commit).
    //   — Đây là thiết kế "eventual consistency": order đã tạo trong DB,
    //     nếu Kafka down thì consumer không nhận event, nhưng scheduler sẽ handle sau.
    @Transactional
    public CreateOrderResponse checkout(UUID userId, CheckoutRequest request) {

        // Bước 1 — Idempotency check:
        // Nếu client gửi cùng idempotencyKey (do network retry), throw DuplicateOrderException.
        // GlobalExceptionHandler trả về 409 CONFLICT — client biết đã có order.
        // Không return order cũ vì: nếu payment đã xong thì redirect khác.
        if (request.getIdempotencyKey() != null) {
            Optional<Order> existing = orderRepo.findByIdempotencyKey(request.getIdempotencyKey());
            if (existing.isPresent()) {
                Order order = existing.get();
                throw new DuplicateOrderException(order.getId().toString());
            }
        }

        // Bước 2 — Lấy giỏ hàng:
        // CartService.getCart() đọc từ Redis (hoặc DB tùy implement).
        // Nếu cart rỗng, throw EmptyCartException → 400 BAD_REQUEST.
        CartDto cart = cartService.getCart(userId);
        if (cart.getItems().isEmpty()) {
            throw new EmptyCartException("Giỏ hàng trống");
        }

        // Bước 3 — Validate + reserve inventory:
        //   - Dùng findAllByProductIdInWithLock() — 1 query SELECT ... WHERE id IN (...)
        //     thay vì N query riêng lẻ (giảm N-1 round trips DB).
        //   - Lock PESSIMISTIC_WRITE trên tất cả dòng Inventory cùng lúc.
        //   - Lock theo thứ tự productId (sorted) — tránh deadlock khi 2 user checkout cùng lúc.
        //   - Nếu số lượng trả về != số productId → có product không tồn tại → throw.
        //   - Tính available = quantity - reserved (hàng đã reserve nhưng chưa thanh toán).
        //   - Nếu không đủ → throw InsufficientInventoryException → 409 CONFLICT.
        //   - Nếu đủ: tăng reserved, chưa trừ quantity thật.
        //     → quantity chỉ trừ sau khi payment success (ở InventoryConsumer).
        List<UUID> productIds = cart.getItems().stream()
            .map(CartItemDto::getProductId)
            .sorted()
            .toList();

        List<Inventory> inventories = inventoryRepo.findAllByProductIdInWithLock(productIds);
        if (inventories.size() != productIds.size()) {
            List<UUID> foundIds = inventories.stream()
                .map(inv -> inv.getProduct().getId())
                .toList();
            UUID missing = productIds.stream()
                .filter(pid -> !foundIds.contains(pid))
                .findFirst()
                .orElse(null);
            throw new ProductNotFoundException("Product: " + missing);
        }

        Map<UUID, CartItemDto> cartItemMap = cart.getItems().stream()
            .collect(Collectors.toMap(CartItemDto::getProductId, Function.identity()));
        Map<UUID, Inventory> inventoryMap = new HashMap<>();
        for (Inventory inv : inventories) {
            CartItemDto item = cartItemMap.get(inv.getProduct().getId());
            int available = inv.getQuantity() - inv.getReserved();
            if (available < item.getQuantity()) {
                throw new InsufficientInventoryException(
                    "Sản phẩm " + item.getProductName() + " không đủ hàng");
            }
            inv.setReserved(inv.getReserved() + item.getQuantity());
            inventoryMap.put(inv.getProduct().getId(), inv);
        }
        inventoryRepo.saveAll(inventoryMap.values());

        // Bước 4 — Tạo Order entity:
        // buildOrder() snapshot dữ liệu từ cart (giá, tên sản phẩm) vào order_items.
        //   — Lý do snapshot: giá sản phẩm có thể thay đổi sau này, order phải giữ giá gốc.
        // Order status khởi tạo = PENDING_PAYMENT.
        Order order = buildOrder(userId, cart, request);
        orderRepo.save(order);

        // Bước 5 — Xóa giỏ hàng sau khi đặt thành công.
        cartService.clearCart(userId);

        // Bước 6 — Publish event sau khi DB commit:
        // TransactionSynchronizationManager.registerSynchronization() đăng ký callback.
        // afterCommit(): chạy sau khi transaction chính commit thành công.
        //   — Nếu transaction rollback (exception ở bước 3/4), afterCommit không được gọi.
        //   — Đảm bảo: event chỉ được publish khi dữ liệu đã an toàn trong DB.
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    eventProducer.publishOrderCreated(order);
                }
            }
        );

        log.info("Order created: {} for user: {}", order.getOrderNumber(), userId);
        return CreateOrderResponse.builder()
            .orderId(order.getId())
            .orderNumber(order.getOrderNumber())
            .total(order.getTotal())
            .paymentUrl(generatePaymentUrl(order))
            .status(order.getStatus().name())
            .build();
    }

    // === GET USER ORDERS (READ-ONLY) ===
    // @Transactional(readOnly = true): hint cho Hibernate bỏ dirty checking, tăng tốc.
    // Phân trang + sort theo createdAt DESC — order mới nhất lên đầu.
    @Transactional(readOnly = true)
    public Page<OrderSummaryDto> getUserOrders(UUID userId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return orderRepo.findByUserIdOrderByCreatedAtDesc(userId, pageable)
            .map(OrderSummaryDto::from);
    }

    // === GET ORDER DETAIL (READ-ONLY) ===
    // Dùng findByIdWithItems() — LEFT JOIN FETCH để tránh N+1 query.
    // Kiểm tra userId để đảm bảo user chỉ xem được order của chính họ.
    // Nếu không match: throw OrderNotFoundException (không throw access denied vì không muốn leak thông tin).
    @Transactional(readOnly = true)
    public OrderDetailDto getOrderDetail(UUID orderId, UUID userId) {
        Order order = orderRepo.findByIdWithItems(orderId)
            .orElseThrow(() -> new OrderNotFoundException("Order not found: " + orderId));

        if (!order.getUserId().equals(userId)) {
            throw new OrderNotFoundException("Order not found: " + orderId);
        }

        return OrderDetailDto.from(order);
    }

    // === PRIVATE HELPERS ===

    // buildOrder: snapshot dữ liệu từ CartDto + CheckoutRequest → Order entity.
    //   - ShippingAddress: snapshot từ JSON (dùng ObjectMapper.valueToTree).
    //     → Nếu user đổi địa chỉ sau, order vẫn giữ địa chỉ cũ.
    //   - OrderItem: copy productName, unitPrice từ cart (snapshot giá tại thời điểm đặt).
    //   - subtotal: sum(items.subtotal) — đã tính trong cart.
    //   - shippingFee: free nếu >= 500k, else 30k.
    //   - discount: Phase 4+ mới có (coupon/voucher), giờ để ZERO.
    private Order buildOrder(UUID userId, CartDto cart, CheckoutRequest req) {
        Order order = new Order();
        order.setOrderNumber(generateOrderNumber());
        order.setUserId(userId);
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        order.setIdempotencyKey(req.getIdempotencyKey());
        order.setShippingAddress(objectMapper.valueToTree(req.getShippingAddress()));
        order.setNotes(req.getNotes());

        List<OrderItem> items = cart.getItems().stream().map(ci -> {
            OrderItem item = new OrderItem();
            item.setOrder(order);
            item.setProductId(ci.getProductId());
            item.setProductName(ci.getProductName());
            item.setProductImage(ci.getImage());
            item.setQuantity(ci.getQuantity());
            item.setUnitPrice(ci.getPrice());
            item.setSubtotal(ci.getPrice().multiply(BigDecimal.valueOf(ci.getQuantity())));
            return item;
        }).toList();

        order.setItems(items);
        order.setSubtotal(cart.getTotal());
        order.setShippingFee(calculateShippingFee(cart));
        order.setDiscount(BigDecimal.ZERO);
        order.setTotal(order.getSubtotal().add(order.getShippingFee()));
        return order;
    }

    // Miễn phí ship nếu tổng đơn >= 500k, ngược lại 30k.
    private BigDecimal calculateShippingFee(CartDto cart) {
        if (cart.getTotal().compareTo(new BigDecimal("500000")) >= 0) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal("30000");
    }

    // Sinh mã đơn hàng: ORD-yyyyMMdd-xxxxxx (vd: ORD-20240115-001234).
    // RANDOM.nextInt(999999) có padding 6 số → unique trong 1 ngày là đủ (1M order/ngày).
    // Không dùng sequence DB: tránh phụ thuộc DB cho việc tạo mã hiển thị.
    private String generateOrderNumber() {
        return "ORD-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
            + "-" + String.format("%06d", RANDOM.nextInt(999999));
    }

    // Tạo URL thanh toán — Phase 3 mock, trỏ thẳng đến PaymentController.
    // Phase 4+: sẽ thay bằng URL thật từ VNPAY/MOMO.
    private String generatePaymentUrl(Order order) {
        return "/api/v1/payments/confirm?orderId=" + order.getId();
    }
}
```

### PaymentService.java

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    // PaymentService: xử lý thanh toán (mock trong Phase 3).
    // Flow: nhận request → idempotency check → validate order → mock payment → update order → publish event.
    // Điểm quan trọng: payment và order status update nằm trong cùng 1 transaction.
    //   — Nếu payment save fail → order không bị update → nhất quán.
    private final OrderRepository orderRepo;
    private final PaymentRepository paymentRepo;
    private final PaymentEventProducer eventProducer;

    // === CONFIRM PAYMENT ===
    // @Transactional: toàn bộ method trong 1 transaction.
    // Nếu bất kỳ step nào fail → rollback tất cả: Payment record, Order status đều không đổi.
    // Ngoại lệ: Kafka publish (step 5) tách ra sau commit — xử lý async.
    @Transactional
    public PaymentConfirmResponse confirmPayment(PaymentConfirmRequest request) {

        // Bước 1 — Idempotency check:
        // Khác với OrderService (throw exception), PaymentService return response cũ.
        //   — Lý do: nếu client retry POST /payments/confirm, họ muốn biết kết quả, không phải lỗi.
        //   — Trả success=true, kèm paymentId + transactionId của record cũ.
        // Idempotency key lưu ở Payment, không phải Order.
        //   — Lý do: 1 order có thể có nhiều payment attempt (nếu lần đầu fail).
        if (request.getIdempotencyKey() != null) {
            Optional<Payment> existing = paymentRepo.findByIdempotencyKey(request.getIdempotencyKey());
            if (existing.isPresent()) {
                Payment payment = existing.get();
                return PaymentConfirmResponse.builder()
                    .success(true)
                    .paymentId(payment.getId())
                    .transactionId(payment.getGatewayTransactionId())
                    .status(payment.getStatus().name())
                    .message("Payment already processed")
                    .build();
            }
        }

        // Bước 2 — Validate order:
        //   - Order phải tồn tại.
        //   - Order status phải là PENDING_PAYMENT (chưa thanh toán, chưa hủy).
        //   - Nếu status = PAID → payment đã xử lý rồi → throw PaymentFailedException.
        //   - Nếu status = CANCELLED → không thể thanh toán → throw PaymentFailedException.
        Order order = orderRepo.findById(request.getOrderId())
            .orElseThrow(() -> new OrderNotFoundException("Order not found: " + request.getOrderId()));

        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            throw new PaymentFailedException("Order status is " + order.getStatus() + ", expected PENDING_PAYMENT");
        }

        // Bước 3 — Mock payment:
        // Phase 3: luôn success, không gọi gateway thật.
        // Tạo transactionId giả (MOCK-TXN-XXXXXXXX) để có trace.
        // metadata: lưu thông tin debug (mock=true, processedAt).
        // Phase 4+: thay bằng gọi VNPAY/MOMO API, xử lý callback.
        String transactionId = "MOCK-TXN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        Payment payment = Payment.builder()
            .orderId(order.getId())
            .amount(order.getTotal())
            .method(request.getPaymentMethod())
            .status(PaymentStatus.SUCCESS)
            .gatewayTransactionId(transactionId)
            .idempotencyKey(request.getIdempotencyKey())
            .metadata(objectMapper.createObjectNode()
                .put("mock", true)
                .put("processedAt", Instant.now().toString()))
            .build();

        paymentRepo.save(payment);

        // Bước 4 — Update order status:
        // PENDING_PAYMENT → PAID.
        // Sau step này, order không thể thanh toán lại (step 2 sẽ reject).
        order.setStatus(OrderStatus.PAID);
        orderRepo.save(order);

        // Bước 5 — Publish ORDER_PAID event sau khi DB commit:
        // Tương tự OrderService: dùng TransactionSynchronizationManager.
        //   — afterCommit() chỉ chạy nếu toàn bộ transaction (step 1-4) thành công.
        //   — Nếu bước 2 fail (sai status), transaction rollback, không publish event.
        // Consumer nhận event: InventoryConsumer (deduct) + EmailConsumer + AuditConsumer.
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    eventProducer.publishPaymentConfirmed(order);
                }
            }
        );

        log.info("Payment confirmed: order={}, transaction={}", order.getOrderNumber(), transactionId);

        return PaymentConfirmResponse.builder()
            .success(true)
            .paymentId(payment.getId())
            .transactionId(transactionId)
            .status(PaymentStatus.SUCCESS.name())
            .message("Thanh toán thành công")
            .build();
    }
}
```

---

## 9. Controllers

### OrderController.java

```java
package com.ecommerce.domain.order;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
@Slf4j
public class OrderController {

    private final OrderService orderService;

    @PostMapping("/checkout")
    public ResponseEntity<CreateOrderResponse> checkout(
            @AuthenticationPrincipal UUID userId,
            @Valid @RequestBody CheckoutRequest request) {
        CreateOrderResponse response = orderService.checkout(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<Page<OrderSummaryDto>> getOrders(
            @AuthenticationPrincipal UUID userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(orderService.getUserOrders(userId, page, size));
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderDetailDto> getOrderDetail(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID id) {
        return ResponseEntity.ok(orderService.getOrderDetail(id, userId));
    }
}
```

### PaymentController.java

```java
package com.ecommerce.domain.payment;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
@Slf4j
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping("/confirm")
    public ResponseEntity<PaymentConfirmResponse> confirmPayment(
            @Valid @RequestBody PaymentConfirmRequest request) {
        PaymentConfirmResponse response = paymentService.confirmPayment(request);
        return ResponseEntity.ok(response);
    }
}
```

---

## 10. Consumers (Kafka)

### InventoryConsumer.java

```java
package com.ecommerce.consumer;

@Component
@Slf4j
public class InventoryConsumer {

    // InventoryConsumer: consumer quan trọng nhất trong Saga — duy trì tính nhất quán của inventory.
    // Nhận event từ topic "ecommerce.order.events", groupId = "inventory-service".
    // groupId riêng → mỗi consumer (inventory, email, audit) đều nhận bản copy message.
    //
    // Hai event type xử lý:
    //   - ORDER_PAID:   trừ quantity thật sự (deductInventory).
    //   - ORDER_CANCELLED: release reserved (không trừ quantity).
    // Các event khác (ORDER_CREATED, PAYMENT_FAILED) bỏ qua.
    private final InventoryRepository inventoryRepo;

    // @Transactional: mỗi lần handle là 1 DB transaction riêng.
    // Nếu handle fail (exception) → không ack → Kafka retry → sau max retry → DLQ.
    // Lý do @Transactional ở consumer: đảm bảo DB update và Kafka ack đồng bộ.
    //   - Nếu ack trước DB save: crash sau ack → mất event.
    //   - Nếu DB save trước ack: crash trước ack → retry → idempotent.
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
            // Không ack → Kafka sẽ gửi lại message (retry).
            // Sau max.poll.interval.ms (mặc định 5p) → consumer bị coi là dead → rebalance.
            // Sau max retries → message bị đẩy vào DLQ (Dead Letter Queue).
            // Không throw exception ra ngoài để listener container không crash.
        }
    }

    // deductInventory: gọi khi ORDER_PAID — trừ hàng thật sự.
    // Đây là bước commit cuối cùng của Saga: reserved → sold.
    //   - quantity:  giảm (hàng đã bán).
    //   - reserved:  giảm (không cần reserve nữa).
    // Lưu ý: nếu consumer xử lý trùng (retry), reserved có thể âm.
    //   → Giải pháp: thêm idempotency check (Phase 4+ với Outbox Pattern).
    //   → Hiện tại: dùng PESSIMISTIC_WRITE lock để tránh race condition.
    //
    // Tối ưu: dùng findAllByProductIdInWithLock() — 1 query SELECT ... WHERE id IN (...),
    //   map productId → Inventory, sau đó loop trong memory để update.
    //   Giảm từ N round trips DB xuống 1 (N = số sản phẩm trong đơn).
    private void deductInventory(OrderEvent event) {
        List<UUID> productIds = event.getItems().stream()
            .map(OrderEvent.OrderItemEvent::getProductId)
            .toList();

        Map<UUID, OrderEvent.OrderItemEvent> itemMap = event.getItems().stream()
            .collect(Collectors.toMap(OrderEvent.OrderItemEvent::getProductId, Function.identity()));

        inventoryRepo.findAllByProductIdInWithLock(productIds).forEach(inv -> {
            OrderEvent.OrderItemEvent item = itemMap.get(inv.getProduct().getId());
            inv.setQuantity(inv.getQuantity() - item.getQuantity());
            inv.setReserved(inv.getReserved() - item.getQuantity());
            inventoryRepo.save(inv);

            log.info("Inventory deducted: product={}, qty={}, remaining={}",
                item.getProductId(), item.getQuantity(), inv.getQuantity());
        });
    }

    // releaseReservation: gọi khi ORDER_CANCELLED — giải phóng hàng đã reserve.
    //   - reserved: giảm (không cần giữ chỗ nữa).
    //   - quantity: không đổi (hàng chưa bán, chỉ là hết reserved).
    // Đây là rollback của bước reserve trong checkout.
    // Lưu ý: nếu payment timeout (30 phút), scheduler publish ORDER_CANCELLED,
    // consumer này releaseReservation để hàng có sẵn cho người mua khác.
    //
    // Tối ưu: 1 batch query thay vì N query riêng lẻ (giống deductInventory).
    private void releaseReservation(OrderEvent event) {
        List<UUID> productIds = event.getItems().stream()
            .map(OrderEvent.OrderItemEvent::getProductId)
            .toList();

        Map<UUID, OrderEvent.OrderItemEvent> itemMap = event.getItems().stream()
            .collect(Collectors.toMap(OrderEvent.OrderItemEvent::getProductId, Function.identity()));

        inventoryRepo.findAllByProductIdInWithLock(productIds).forEach(inv -> {
            OrderEvent.OrderItemEvent item = itemMap.get(inv.getProduct().getId());
            inv.setReserved(inv.getReserved() - item.getQuantity());
            inventoryRepo.save(inv);

            log.info("Reservation released: product={}, qty={}, reserved={}",
                item.getProductId(), item.getQuantity(), inv.getReserved());
        });
    }
}
```

### EmailConsumer.java

```java
package com.ecommerce.consumer;

@Component
@Slf4j
public class EmailConsumer {

    // EmailConsumer: gửi email thông báo cho user.
    // Không có @Transactional — vì không thao tác DB, chỉ log và gọi email service.
    // Không có @Transactional → nếu email service fail (exception), không ack → retry.
    //
    // Ba event type xử lý:
    //   - ORDER_CREATED:  email "Đơn hàng đã tạo, chờ thanh toán".
    //   - ORDER_PAID:     email "Thanh toán thành công".
    //   - ORDER_CANCELLED: email "Đơn hàng đã hủy" kèm lý do.
    //
    // Phase 3: mock — chỉ log ra console.
    // Phase 4+: tích hợp SMTP (JavaMailSender) hoặc email service (SendGrid, AWS SES).
    @KafkaListener(topics = "ecommerce.order.events", groupId = "email-service")
    public void handle(@Payload OrderEvent event, Acknowledgment ack) {
        try {
            switch (event.getEventType()) {
                case "ORDER_CREATED" -> sendOrderConfirmation(event);
                case "ORDER_PAID" -> sendPaymentConfirmation(event);
                case "ORDER_CANCELLED" -> sendCancellationNotice(event);
            }
            ack.acknowledge();
        } catch (Exception e) {
            log.error("EmailConsumer failed for order={}: {}", event.getOrderId(), e.getMessage());
            // Không ack → retry. Email là non-critical → có thể bỏ qua sau N lần retry.
            // DLQ: Phase 4+ cần alert để operator xử lý thủ công.
        }
    }

    // Gửi email xác nhận đơn hàng — ngay sau khi user checkout thành công.
    // Nội dung: mã đơn, tổng tiền, trạng thái "chờ thanh toán".
    private void sendOrderConfirmation(OrderEvent event) {
        log.info("===== EMAIL: Gửi email xác nhận đơn hàng =====");
        log.info("Đến user: {}", event.getUserId());
        log.info("Mã đơn: {}", event.getOrderNumber());
        log.info("Tổng tiền: {}", event.getTotal());
        log.info("Trạng thái: Chờ thanh toán");
        log.info("==============================================");
    }

    // Gửi email xác nhận thanh toán — sau khi payment success.
    // Nội dung: mã đơn, số tiền đã thanh toán.
    private void sendPaymentConfirmation(OrderEvent event) {
        log.info("===== EMAIL: Gửi email xác nhận thanh toán =====");
        log.info("Đến user: {}", event.getUserId());
        log.info("Mã đơn: {}", event.getOrderNumber());
        log.info("Đã thanh toán: {}", event.getTotal());
        log.info("================================================");
    }

    // Gửi email thông báo hủy đơn — khi order bị cancel (timeout hoặc user hủy).
    // Nội dung: mã đơn, lý do hủy.
    private void sendCancellationNotice(OrderEvent event) {
        log.info("===== EMAIL: Gửi email hủy đơn hàng =====");
        log.info("Đến user: {}", event.getUserId());
        log.info("Mã đơn: {}", event.getOrderNumber());
        log.info("Lý do: {}", event.getReason());
        log.info("==========================================");
    }
}
```

### AuditConsumer.java

```java
package com.ecommerce.consumer;

@Component
@Slf4j
public class AuditConsumer {

    // AuditConsumer: ghi log kiểm toán (audit trail) cho mọi event.
    // groupId = "audit-service" — riêng biệt, không ảnh hưởng đến inventory/email consumer.
    // Xử lý tất cả event types — không switch, log mọi thứ.
    // Phase 3: log ra console. Phase 5+: ghi vào database (bảng audit_logs) hoặc ELK.
    //
    // Audit trail rất quan trọng cho compliance (GDPR, PCI DSS):
    //   - Ai (userId) làm gì (eventType) với cái gì (orderId/orderNumber).
    //   - Khi nào (timestamp) và tại sao (reason).
    //   - Số tiền (total) — phục vụ đối soát (reconciliation).
    @KafkaListener(topics = "ecommerce.order.events", groupId = "audit-service")
    public void handle(@Payload OrderEvent event, Acknowledgment ack) {
        try {
            logAudit(event);
            ack.acknowledge();
        } catch (Exception e) {
            log.error("AuditConsumer failed for order={}: {}", event.getOrderId(), e.getMessage());
            // Audit consumer: không throw exception — audit fail không được block xử lý chính.
            // Nếu lỗi network (logstash down), retry không giúp ích → có thể bỏ qua.
            // Phase 5+: ghi vào file log riêng hoặc DLQ để sau này xử lý.
        }
    }

    // Ghi audit trail: đầy đủ thông tin để trace toàn bộ vòng đời của 1 order.
    // Phase 3: chỉ log INFO.
    // Phase 5+: chuyển sang ghi vào bảng audit_logs (JSONB) cho dễ query.
    private void logAudit(OrderEvent event) {
        log.info("===== AUDIT LOG =====");
        log.info("Event:     {}", event.getEventType());
        log.info("Order:     {} ({})", event.getOrderNumber(), event.getOrderId());
        log.info("User:      {}", event.getUserId());
        log.info("Total:     {}", event.getTotal());
        log.info("Timestamp: {}", event.getTimestamp());
        log.info("Reason:    {}", event.getReason());
        log.info("=====================");
    }
}
```

---

## 11. Exceptions

### DuplicateOrderException.java

```java
package com.ecommerce.exception;

public class DuplicateOrderException extends RuntimeException {
    public DuplicateOrderException(String message) {
        super("Duplicate order: " + message);
    }
}
```

### EmptyCartException.java

```java
package com.ecommerce.exception;

public class EmptyCartException extends RuntimeException {
    public EmptyCartException(String message) {
        super(message);
    }
}
```

### InsufficientInventoryException.java

```java
package com.ecommerce.exception;

public class InsufficientInventoryException extends RuntimeException {
    public InsufficientInventoryException(String message) {
        super(message);
    }
}
```

### PaymentFailedException.java

```java
package com.ecommerce.exception;

public class PaymentFailedException extends RuntimeException {
    public PaymentFailedException(String message) {
        super(message);
    }
}
```

### OrderNotFoundException.java

```java
package com.ecommerce.exception;

public class OrderNotFoundException extends RuntimeException {
    public OrderNotFoundException(String message) {
        super(message);
    }
}
```

### GlobalExceptionHandler — bổ sung

```java
package com.ecommerce.exception;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(DuplicateOrderException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleDuplicateOrder(DuplicateOrderException e) {
        return new ErrorResponse("DUPLICATE_ORDER", e.getMessage());
    }

    @ExceptionHandler(EmptyCartException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleEmptyCart(EmptyCartException e) {
        return new ErrorResponse("EMPTY_CART", e.getMessage());
    }

    @ExceptionHandler(InsufficientInventoryException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleInsufficientInventory(InsufficientInventoryException e) {
        return new ErrorResponse("INSUFFICIENT_INVENTORY", e.getMessage());
    }

    @ExceptionHandler(PaymentFailedException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handlePaymentFailed(PaymentFailedException e) {
        return new ErrorResponse("PAYMENT_FAILED", e.getMessage());
    }

    @ExceptionHandler(OrderNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleOrderNotFound(OrderNotFoundException e) {
        return new ErrorResponse("ORDER_NOT_FOUND", e.getMessage());
    }
}
```

---

## 12. Frontend

> Tổng quan luồng FE: checkout → order-confirmation (chờ thanh toán) → payment (mock) → order-confirmation (đã thanh toán) → order-history → order-detail.  
> **5 HTML + 4 JS + 1 CSS = 10 files**.

### js/api.js

```javascript
// HTTP client base — wrapper quanh fetch(), tự động gắn auth token + parse JSON.
// Lưu ý: tất cả API call trong Phase 3 đều dùng apiFetch().
// Nếu server trả về lỗi (status >= 400), throw error với message từ response body.
const API_BASE = '';

export async function apiFetch(path, options = {}) {
    const token = localStorage.getItem('authToken');
    const headers = {
        'Content-Type': 'application/json',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...options.headers,
    };

    const res = await fetch(`${API_BASE}/api/v1${path}`, {
        ...options,
        headers,
    });

    if (!res.ok) {
        const err = await res.json().catch(() => ({ message: res.statusText }));
        throw new Error(err.message || `HTTP ${res.status}`);
    }

    return res.json();
}

// Hàm tiện ích: format tiền tệ VND, dùng chung cho tất cả JS files.
export function formatVND(amount) {
    return new Intl.NumberFormat('vi-VN', {
        style: 'currency',
        currency: 'VND',
    }).format(amount);
}

// Hàm tiện ích: map status ENUM → tiếng Việt, dùng chung.
export function statusText(status) {
    const map = {
        PENDING_PAYMENT: 'Chờ thanh toán',
        PAID: 'Đã thanh toán',
        PROCESSING: 'Đang xử lý',
        SHIPPED: 'Đang giao',
        DELIVERED: 'Đã giao',
        CANCELLED: 'Đã hủy',
        REFUNDED: 'Đã hoàn tiền',
    };
    return map[status] || status;
}
```

### js/checkout.js

```javascript
import { apiFetch, formatVND } from './api.js';

// checkout.html: Load cart summary + xử lý submit form đặt hàng.
// generateIdempotencyKey() tạo UUID client-side — nếu user bấm Đặt hàng 2 lần,
// server check idempotencyKey trùng → throw 409 → alert lỗi.
const form = document.getElementById('checkout-form');
const cartSummary = document.getElementById('cart-summary');

async function loadCartSummary() {
    try {
        const cart = await apiFetch('/cart');
        let html = `<p>Số lượng: <strong>${cart.itemCount}</strong> sản phẩm</p>`;
        html += `<table>
            <thead><tr><th>Sản phẩm</th><th>SL</th><th>Đơn giá</th><th>Thành tiền</th></tr></thead>
            <tbody>`;
        cart.items.forEach(item => {
            html += `<tr>
                <td>${item.productName}</td>
                <td>${item.quantity}</td>
                <td>${formatVND(item.price)}</td>
                <td>${formatVND(item.price * item.quantity)}</td>
            </tr>`;
        });
        html += `</tbody></table>`;
        html += `<p class="cart-total">Tổng cộng: <strong>${formatVND(cart.total)}</strong></p>`;
        cartSummary.innerHTML = html;
    } catch (err) {
        cartSummary.innerHTML = '<p class="error">Không thể tải giỏ hàng</p>';
    }
}

function generateIdempotencyKey() {
    return crypto.randomUUID();
}

form.addEventListener('submit', async (e) => {
    e.preventDefault();
    const btn = document.getElementById('checkout-btn');
    btn.disabled = true;
    btn.textContent = 'Đang xử lý...';

    const idempotencyKey = generateIdempotencyKey();
    const paymentMethod = document.getElementById('paymentMethod').value;

    try {
        const response = await apiFetch('/orders/checkout', {
            method: 'POST',
            body: JSON.stringify({
                idempotencyKey,
                shippingAddress: {
                    fullName: document.getElementById('fullName').value,
                    phone: document.getElementById('phone').value,
                    address: document.getElementById('address').value,
                    province: document.getElementById('province').value,
                    district: document.getElementById('district').value,
                },
                notes: document.getElementById('notes').value,
            }),
        });

        // Lưu response + paymentMethod vào sessionStorage để payment.html dùng.
        sessionStorage.setItem('lastOrder', JSON.stringify(response));
        sessionStorage.setItem('paymentMethod', paymentMethod);
        sessionStorage.setItem('idempotencyKey', idempotencyKey);

        // Chuyển đến payment page (mock) — gọi POST /payments/confirm.
        window.location.href = '/payment.html';
    } catch (err) {
        alert('Lỗi: ' + err.message);
        btn.disabled = false;
        btn.textContent = 'Đặt hàng';
    }
});

loadCartSummary();
```

### js/payment.js

```javascript
import { apiFetch, formatVND } from './api.js';

// payment.html: Xác nhận thanh toán (mock) — gọi POST /api/v1/payments/confirm.
// Phase 3: luôn success ngay. Phase 4+: redirect sang VNPAY/MOMO, xử lý callback.
const lastOrder = JSON.parse(sessionStorage.getItem('lastOrder'));
const idempotencyKey = sessionStorage.getItem('idempotencyKey');
const paymentMethod = sessionStorage.getItem('paymentMethod');

// Nếu không có order → redirect về products.
if (!lastOrder) {
    window.location.href = '/products.html';
}

const orderInfo = document.getElementById('order-info');
const payBtn = document.getElementById('pay-btn');
const payResult = document.getElementById('pay-result');

// Hiển thị thông tin đơn hàng trước khi thanh toán.
orderInfo.innerHTML = `
    <p>Mã đơn hàng: <strong>${lastOrder.orderNumber}</strong></p>
    <p>Số tiền: <strong>${formatVND(lastOrder.total)}</strong></p>
    <p>Phương thức: <strong>${paymentMethod === 'COD' ? 'Thanh toán khi nhận hàng'
        : paymentMethod === 'BANK_TRANSFER' ? 'Chuyển khoản'
        : paymentMethod === 'VNPAY' ? 'VNPay'
        : paymentMethod === 'MOMO' ? 'Ví MoMo'
        : paymentMethod}</strong></p>
`;

payBtn.addEventListener('click', async () => {
    payBtn.disabled = true;
    payBtn.textContent = 'Đang xử lý thanh toán...';

    try {
        const response = await apiFetch('/payments/confirm', {
            method: 'POST',
            body: JSON.stringify({
                orderId: lastOrder.orderId,
                paymentMethod: paymentMethod || 'COD',
                idempotencyKey,
            }),
        });

        // Thanh toán thành công — lưu kết quả + chuyển sang confirmation.
        sessionStorage.setItem('paymentResult', JSON.stringify({
            ...response,
            orderNumber: lastOrder.orderNumber,
            total: lastOrder.total,
        }));

        window.location.href = '/order-confirmation.html';
    } catch (err) {
        payResult.innerHTML = `<p class="error">Thanh toán thất bại: ${err.message}</p>`;
        payBtn.disabled = false;
        payBtn.textContent = 'Thử lại';
    }
});
```

### js/order.js

```javascript
import { apiFetch, formatVND, statusText } from './api.js';

// === order-confirmation.html ===
// Phase 3: hiển thị kết quả sau khi checkout hoặc payment.
//   - Nếu có paymentResult: hiển thị "Thanh toán thành công".
//   - Nếu chỉ có lastOrder: hiển thị "Đặt hàng thành công, chờ thanh toán".
const orderNumber = document.getElementById('order-number');
const orderTotal = document.getElementById('order-total');
const confirmationTitle = document.getElementById('confirmation-title');
const confirmationMsg = document.getElementById('confirmation-msg');

if (confirmationTitle) {
    const paymentResult = JSON.parse(sessionStorage.getItem('paymentResult'));
    const lastOrder = JSON.parse(sessionStorage.getItem('lastOrder'));

    if (paymentResult && paymentResult.success) {
        confirmationTitle.textContent = 'Thanh toán thành công!';
        confirmationMsg.innerHTML = `
            <p>Mã giao dịch: <strong>${paymentResult.transactionId}</strong></p>
            <p>Cảm ơn bạn đã mua hàng. Email xác nhận đã được gửi.</p>
        `;
        orderNumber.textContent = paymentResult.orderNumber;
        orderTotal.textContent = formatVND(paymentResult.total);

        // Clear sessionStorage để không hiển thị lại khi refresh.
        sessionStorage.removeItem('paymentResult');
        sessionStorage.removeItem('lastOrder');
        sessionStorage.removeItem('paymentMethod');
        sessionStorage.removeItem('idempotencyKey');
    } else if (lastOrder) {
        orderNumber.textContent = lastOrder.orderNumber;
        orderTotal.textContent = formatVND(lastOrder.total);
    } else {
        window.location.href = '/products.html';
    }
}

// === order-history.html ===
const orderList = document.getElementById('order-list');
const pagination = document.getElementById('pagination');

if (orderList) {
    loadOrders();

    async function loadOrders(page = 0) {
        try {
            const data = await apiFetch(`/orders?page=${page}&size=10`);
            renderOrders(data, page);
        } catch (err) {
            orderList.innerHTML = '<p class="error">Không thể tải lịch sử đơn hàng</p>';
        }
    }

    function renderOrders(data, currentPage) {
        if (data.content.length === 0) {
            orderList.innerHTML = '<p>Chưa có đơn hàng nào.</p>';
            pagination.innerHTML = '';
            return;
        }

        let html = '<table><thead><tr><th>Mã đơn</th><th>Trạng thái</th><th>Tổng tiền</th><th>Ngày</th></tr></thead><tbody>';
        data.content.forEach(order => {
            const statusClass = order.status === 'CANCELLED' ? 'status-cancelled'
                : order.status === 'PAID' ? 'status-paid'
                : order.status === 'DELIVERED' ? 'status-delivered'
                : '';
            html += `<tr onclick="window.location='/order-detail.html?id=${order.id}'" class="${statusClass}">
                <td>${order.orderNumber}</td>
                <td><span class="badge badge-${order.status.toLowerCase()}">${statusText(order.status)}</span></td>
                <td>${formatVND(order.total)}</td>
                <td>${new Date(order.createdAt).toLocaleDateString('vi-VN')}</td>
            </tr>`;
        });
        html += '</tbody></table>';
        orderList.innerHTML = html;

        // Phân trang đơn giản.
        if (data.totalPages > 1) {
            let pagHtml = '<div class="pagination">';
            for (let i = 0; i < data.totalPages; i++) {
                pagHtml += `<button onclick="loadOrders(${i})" class="${i === currentPage ? 'active' : ''}">${i + 1}</button>`;
            }
            pagHtml += '</div>';
            pagination.innerHTML = pagHtml;
        } else {
            pagination.innerHTML = '';
        }
    }
}

// === order-detail.html ===
const orderDetail = document.getElementById('order-detail');
if (orderDetail) {
    loadOrderDetail();

    async function loadOrderDetail() {
        const params = new URLSearchParams(window.location.search);
        const orderId = params.get('id');

        if (!orderId) {
            orderDetail.innerHTML = '<p class="error">Không tìm thấy đơn hàng.</p>';
            return;
        }

        try {
            const order = await apiFetch(`/orders/${orderId}`);
            renderDetail(order);
        } catch (err) {
            orderDetail.innerHTML = '<p class="error">Không thể tải chi tiết đơn hàng.</p>';
        }
    }

    function renderDetail(order) {
        let html = `
            <div class="order-header">
                <h2>Đơn hàng ${order.orderNumber}</h2>
                <span class="badge badge-${order.status.toLowerCase()}">${statusText(order.status)}</span>
            </div>
            <div class="order-meta">
                <p>Ngày đặt: ${new Date(order.createdAt).toLocaleString('vi-VN')}</p>
                <p>Cập nhật: ${new Date(order.updatedAt).toLocaleString('vi-VN')}</p>
            </div>
            <h3>Sản phẩm</h3>
            <table>
                <thead><tr><th>Sản phẩm</th><th>SL</th><th>Đơn giá</th><th>Thành tiền</th></tr></thead>
                <tbody>`;
        order.items.forEach(item => {
            html += `<tr>
                <td>
                    ${item.productImage ? `<img src="${item.productImage}" class="thumb" />` : ''}
                    ${item.productName}
                </td>
                <td>${item.quantity}</td>
                <td>${formatVND(item.unitPrice)}</td>
                <td>${formatVND(item.subtotal)}</td>
            </tr>`;
        });
        html += `</tbody></table>
            <div class="order-summary">
                <p>Tạm tính: ${formatVND(order.subtotal)}</p>
                <p>Phí vận chuyển: ${formatVND(order.shippingFee)}</p>
                <p>Giảm giá: ${formatVND(order.discount)}</p>
                <p class="total">Tổng cộng: ${formatVND(order.total)}</p>
            </div>`;

        if (order.notes) {
            html += `<div class="order-notes"><h3>Ghi chú</h3><p>${order.notes}</p></div>`;
        }

        // Nếu status = PENDING_PAYMENT, hiển thị nút thanh toán lại.
        if (order.status === 'PENDING_PAYMENT') {
            html += `<button onclick="window.location='/payment.html?id=${order.id}'" class="btn btn-primary">
                Thanh toán ngay
            </button>`;
        }

        orderDetail.innerHTML = html;
    }
}
```

### css/main.css

```css
/* === Phase 3 FE Styles === */
/* Reset & Base */
* { margin: 0; padding: 0; box-sizing: border-box; }
body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
       background: #f5f5f5; color: #333; line-height: 1.6; }
.container { max-width: 960px; margin: 0 auto; padding: 20px; }

/* Typography */
h1 { font-size: 1.5rem; margin-bottom: 1rem; color: #222; }
h2 { font-size: 1.2rem; margin: 1.5rem 0 0.5rem; color: #444; }
h3 { font-size: 1rem; margin: 1rem 0 0.5rem; color: #555; }

/* Forms */
.form-group { margin-bottom: 0.75rem; }
.form-group label { display: block; font-size: 0.9rem; font-weight: 500; margin-bottom: 0.25rem; color: #555; }
.form-group input, .form-group select, .form-group textarea {
    width: 100%; padding: 0.5rem 0.75rem; border: 1px solid #ddd; border-radius: 6px;
    font-size: 0.95rem; transition: border-color 0.2s;
}
.form-group input:focus, .form-group select:focus, .form-group textarea:focus {
    outline: none; border-color: #4a90d9; box-shadow: 0 0 0 2px rgba(74,144,217,0.15);
}
.form-group textarea { min-height: 80px; resize: vertical; }
.form-row { display: flex; gap: 1rem; }
.form-row .form-group { flex: 1; }

/* Buttons */
button, .btn {
    display: inline-block; padding: 0.6rem 1.5rem; border: none; border-radius: 6px;
    font-size: 0.95rem; font-weight: 600; cursor: pointer; text-decoration: none;
    background: #4a90d9; color: #fff; transition: background 0.2s, opacity 0.2s;
}
button:hover, .btn:hover { background: #357abd; }
button:disabled, .btn:disabled { opacity: 0.6; cursor: not-allowed; }
.btn-primary { background: #4a90d9; }
.btn-primary:hover { background: #357abd; }
.btn-danger { background: #e74c3c; }
.btn-danger:hover { background: #c0392b; }

/* Tables */
table { width: 100%; border-collapse: collapse; margin: 1rem 0; background: #fff; border-radius: 8px; overflow: hidden; box-shadow: 0 1px 3px rgba(0,0,0,0.08); }
th, td { padding: 0.65rem 0.75rem; text-align: left; border-bottom: 1px solid #eee; font-size: 0.9rem; }
th { background: #f8f9fa; font-weight: 600; color: #555; font-size: 0.85rem; text-transform: uppercase; }
tr:hover { background: #f0f7ff; cursor: pointer; }
tr.status-cancelled { opacity: 0.65; }
tr.status-delivered td:last-child { color: #27ae60; font-weight: 600; }

/* Badges */
.badge { display: inline-block; padding: 0.2rem 0.6rem; border-radius: 12px;
         font-size: 0.8rem; font-weight: 600; }
.badge.pending_payment { background: #fff3cd; color: #856404; }
.badge.paid { background: #d4edda; color: #155724; }
.badge.processing { background: #cce5ff; color: #004085; }
.badge.shipped { background: #d6d8db; color: #383d41; }
.badge.delivered { background: #d4edda; color: #155724; }
.badge.cancelled { background: #f8d7da; color: #721c24; }
.badge.refunded { background: #e2e3e5; color: #383d41; }

/* Cart / Order Summary */
.cart-total { font-size: 1.1rem; text-align: right; margin-top: 0.5rem; }
.order-summary { background: #f8f9fa; padding: 1rem; border-radius: 8px; margin: 1rem 0; }
.order-summary p { display: flex; justify-content: space-between; margin: 0.25rem 0; font-size: 0.9rem; }
.order-summary .total { font-size: 1.1rem; font-weight: 700; border-top: 2px solid #ddd; padding-top: 0.5rem; margin-top: 0.5rem; }

/* Confirmation Page */
.confirmation-box { text-align: center; background: #fff; padding: 2rem; border-radius: 8px; box-shadow: 0 1px 3px rgba(0,0,0,0.08); margin: 2rem 0; }
.confirmation-box p { margin: 0.5rem 0; font-size: 1.1rem; }
.confirmation-box p:first-child { font-size: 1.3rem; font-weight: 600; }
.confirmation-title { text-align: center; color: #27ae60; }
.confirmation-msg { text-align: center; color: #666; margin: 1rem 0; }

/* Order Detail */
.order-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 1rem; }
.order-meta { color: #888; font-size: 0.85rem; margin-bottom: 1rem; }
.thumb { width: 40px; height: 40px; object-fit: cover; border-radius: 4px; margin-right: 0.5rem; vertical-align: middle; }
.order-notes { background: #fffbe6; border: 1px solid #ffe58f; border-radius: 8px; padding: 1rem; margin: 1rem 0; }

/* Payment Page */
.payment-box { text-align: center; background: #fff; padding: 2rem; border-radius: 8px; box-shadow: 0 1px 3px rgba(0,0,0,0.08); margin: 2rem 0; }
#pay-btn { margin-top: 1.5rem; font-size: 1.1rem; padding: 0.8rem 3rem; }
#pay-result { margin-top: 1rem; }

/* Pagination */
.pagination { text-align: center; margin: 1rem 0; }
.pagination button { margin: 0 0.25rem; padding: 0.4rem 0.8rem; background: #fff; color: #333; border: 1px solid #ddd; border-radius: 4px; font-size: 0.85rem; }
.pagination button.active { background: #4a90d9; color: #fff; border-color: #4a90d9; }
.pagination button:hover:not(.active) { background: #f0f7ff; }

/* Utilities */
.error { color: #e74c3c; font-weight: 500; }
```

### checkout.html

```html
<!DOCTYPE html>
<html lang="vi">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Thanh toán - E-Commerce</title>
    <link rel="stylesheet" href="/css/main.css">
</head>
<body>
    <div class="container">
        <h1>Xác nhận đơn hàng</h1>
        <div id="cart-summary"></div>

        <form id="checkout-form">
            <h2>Địa chỉ giao hàng</h2>
            <div class="form-group">
                <label>Họ tên người nhận</label>
                <input type="text" id="fullName" required>
            </div>
            <div class="form-group">
                <label>Số điện thoại</label>
                <input type="tel" id="phone" required>
            </div>
            <div class="form-group">
                <label>Địa chỉ</label>
                <input type="text" id="address" required>
            </div>
            <div class="form-row">
                <div class="form-group">
                    <label>Tỉnh/Thành phố</label>
                    <input type="text" id="province" required>
                </div>
                <div class="form-group">
                    <label>Quận/Huyện</label>
                    <input type="text" id="district" required>
                </div>
            </div>
            <div class="form-group">
                <label>Ghi chú</label>
                <textarea id="notes"></textarea>
            </div>

            <h2>Phương thức thanh toán</h2>
            <div class="form-group">
                <select id="paymentMethod">
                    <option value="COD">Thanh toán khi nhận hàng (COD)</option>
                    <option value="BANK_TRANSFER">Chuyển khoản ngân hàng</option>
                    <option value="VNPAY">VNPay</option>
                    <option value="MOMO">Ví MoMo</option>
                </select>
            </div>

            <button type="submit" id="checkout-btn">Đặt hàng</button>
        </form>
    </div>

    <script type="module" src="/js/api.js"></script>
    <script type="module" src="/js/checkout.js"></script>
</body>
</html>
```

### payment.html

```html
<!DOCTYPE html>
<html lang="vi">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Xác nhận thanh toán - E-Commerce</title>
    <link rel="stylesheet" href="/css/main.css">
</head>
<body>
    <div class="container">
        <h1>Xác nhận thanh toán</h1>
        <div class="payment-box">
            <div id="order-info"></div>
            <button id="pay-btn">Xác nhận thanh toán</button>
            <div id="pay-result"></div>
        </div>
        <a href="/order-history.html" class="btn">Quay lại đơn hàng</a>
    </div>
    <script type="module" src="/js/api.js"></script>
    <script type="module" src="/js/payment.js"></script>
</body>
</html>
```

### order-confirmation.html

```html
<!DOCTYPE html>
<html lang="vi">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Kết quả đơn hàng - E-Commerce</title>
    <link rel="stylesheet" href="/css/main.css">
</head>
<body>
    <div class="container">
        <h1 id="confirmation-title" class="confirmation-title">Đặt hàng thành công!</h1>
        <div class="confirmation-box">
            <p>Mã đơn hàng: <strong id="order-number"></strong></p>
            <p>Tổng tiền: <strong id="order-total"></strong></p>
            <div id="confirmation-msg" class="confirmation-msg">
                <p>Vui lòng kiểm tra email để xác nhận đơn hàng.</p>
            </div>
        </div>
        <div style="text-align: center; margin-top: 1rem;">
            <a href="/order-history.html" class="btn">Xem đơn hàng của tôi</a>
            <a href="/products.html" class="btn" style="margin-left: 0.5rem;">Tiếp tục mua sắm</a>
        </div>
    </div>
    <script type="module" src="/js/api.js"></script>
    <script type="module" src="/js/order.js"></script>
</body>
</html>
```

### order-history.html

```html
<!DOCTYPE html>
<html lang="vi">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Lịch sử đơn hàng - E-Commerce</title>
    <link rel="stylesheet" href="/css/main.css">
</head>
<body>
    <div class="container">
        <h1>Lịch sử đơn hàng</h1>
        <div id="order-list"></div>
        <div id="pagination"></div>
    </div>
    <script type="module" src="/js/api.js"></script>
    <script type="module" src="/js/order.js"></script>
</body>
</html>
```

### order-detail.html

```html
<!DOCTYPE html>
<html lang="vi">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Chi tiết đơn hàng - E-Commerce</title>
    <link rel="stylesheet" href="/css/main.css">
</head>
<body>
    <div class="container">
        <div id="order-detail"></div>
        <a href="/order-history.html" class="btn" style="margin-top: 1rem;">← Quay lại danh sách</a>
    </div>
    <script type="module" src="/js/api.js"></script>
    <script type="module" src="/js/order.js"></script>
</body>
</html>
```

---

## 13. Application.yml bổ sung

```yaml
# Bổ sung vào application.yml
spring:
  task:
    scheduling:
      pool:
        size: 2

  kafka:
    consumer:
      group-id: ecommerce-service
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: "com.ecommerce.event"
    listener:
      ack-mode: MANUAL_IMMEDIATE

app:
  order:
    payment-timeout-minutes: 30
    scheduler-interval-ms: 300000
```

---

## 14. Checklist hoàn thành

```
□ POST /api/v1/orders/checkout → tạo order thành công
□ Inventory reserved tăng lên trong DB
□ Kafka UI: ecommerce.order.events có message
□ POST /api/v1/payments/confirm → order status = PAID
□ Sau confirm: inventory quantity giảm, reserved giảm
□ Email consumer log ra "Gửi email confirm đến..."
□ Audit consumer log ra audit trail
□ Gửi cùng request 2 lần (idempotency key giống nhau) → trả kết quả cũ, 1 order
□ Order PENDING_PAYMENT quá 30 phút → tự CANCELLED
□ Sau cancel: inventory reserved giảm (release reservation)
□ GET /api/v1/orders → list orders của user (phân trang)
□ GET /api/v1/orders/{id} → detail kèm items
□ Cache hit sau request thứ 2 (xem Redis)
□ Response time < 200ms cho cached requests
```

---

## 15. Thứ tự implement

```
Day 1: Enums + Entities + Repositories + V4/V5 migrations
Day 2: DTOs + Events + Exceptions + Event Producers
Day 3: OrderService (checkout + getUserOrders + getOrderDetail)
Day 4: PaymentService (mock payment + saga publish)
Day 5: OrderController + PaymentController
Day 6: InventoryConsumer + EmailConsumer + AuditConsumer
Day 7: Frontend: 5 HTML + 4 JS + 1 CSS (checkout, payment, order-confirmation, order-history, order-detail + api/checkout/payment/order + main.css)
Day 8: Testing + verify checklist + fix bugs
```

---

## Tổng kết Phase 3

| Layer | Số file | Trạng thái |
|-------|---------|------------|
| SQL migrations | 2 (V4 + V5) | ✅ Hoàn chỉnh |
| Enums | 3 | ✅ Hoàn chỉnh |
| Entities | 3 | ✅ Hoàn chỉnh |
| Repositories | 3 | ✅ Hoàn chỉnh |
| DTOs | 6 | ✅ Hoàn chỉnh |
| Events | 1 | ✅ Hoàn chỉnh |
| Producers | 2 | ✅ Hoàn chỉnh |
| Services | 2 | ✅ Hoàn chỉnh |
| Controllers | 2 | ✅ Hoàn chỉnh |
| Consumers | 3 | ✅ Hoàn chỉnh |
| Exceptions | 5 | ✅ Hoàn chỉnh |
| Frontend HTML | 5 | ✅ Hoàn chỉnh |
| Frontend JS | 4 | ✅ Hoàn chỉnh |
| Frontend CSS | 1 | ✅ Hoàn chỉnh |
| Config | 1 | ✅ Hoàn chỉnh |
| **Tổng** | **~38 files** | **✅ Hoàn chỉnh** |
