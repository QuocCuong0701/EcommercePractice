package com.ecommercebe.domain.order;

import com.ecommercebe.domain.cart.CartService;
import com.ecommercebe.domain.inventory.Inventory;
import com.ecommercebe.domain.inventory.InventoryRepository;
import com.ecommercebe.dto.*;
import com.ecommercebe.dto.enumtype.OrderStatus;
import com.ecommercebe.exception.*;
import com.ecommercebe.producer.OrderEventProducer;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
// OrderService: xương sống của checkout flow.
// Chịu trách nhiệm: validate → reserve inventory → tạo order → xóa cart → publish event.
// Lưu ý: tất cả logic nằm trong 1 transaction (@Transactional).
// Nếu bất kỳ bước nào fail (vd: hết hàng), toàn bộ rollback — không có side effect.
public class OrderService {

    private final OrderRepository orderRepository;
    private final InventoryRepository inventoryRepository;
    private final CartService cartService;
    private final OrderEventProducer orderEventProducer;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final Random RANDOM = new Random();

    public CreateOrderResponse checkout(UUID userId, CheckoutRequest request) {
        // Bước 1 — Idempotency check
        if (request.getIdempotencyKey() != null) {
            Optional<Order> existing = orderRepository.findByIdempotencyKey(request.getIdempotencyKey());
            if (existing.isPresent()) {
                Order order = existing.get();
                throw new DuplicateOrderException(order.getId().toString());
            }
        }
        // Bước 2 — Lấy giỏ hàng
        CartDto cart = cartService.getCart(userId);
        if (cart.getItems().isEmpty()) {
            throw new EmptyCartException("Giỏ hàng trống");
        }
        // Bước 3 — Validate + reserve inventory
        //   - Tính available = quantity - reserved (hàng đã reserve nhưng chưa thanh toán).
        //   - Nếu không đủ → throw InsufficientInventoryException → 409 CONFLICT.
        //   - Nếu đủ: tăng reserved, chưa trừ quantity thật.
        //     → quantity chỉ trừ sau khi payment success (ở InventoryConsumer).
        List<UUID> productIds = cart.getItems().stream().map(CartItemDto::getProductId).sorted().toList();
        List<Inventory> inventories = inventoryRepository.findAllByProductIdInWithLock(productIds);

        if (inventories.size() != productIds.size()) {
            List<UUID> foundIds = inventories.stream().map(e -> e.getProduct().getId()).toList();
            UUID missing = productIds.stream().filter(id -> !foundIds.contains(id)).findFirst().orElse(null);
            throw new ProductNotFoundException("Product: " + missing);
        }

        Map<UUID, CartItemDto> cartItemMap = cart.getItems().stream()
                .collect(Collectors.toMap(CartItemDto::getProductId, Function.identity()));
        Map<UUID, Inventory> inventoryMap = new HashMap<>();
        for (Inventory inv : inventories) {
            CartItemDto item = cartItemMap.get(inv.getProduct().getId());
            int available = inv.getQuantity() - inv.getReserved();
            if (available < item.getQuantity()) {
                throw new InsufficientInventoryException("Sản phẩm " + item.getProductName() + " không đủ hàng");
            }
            inv.setReserved(inv.getReserved() + item.getQuantity());
            inventoryMap.put(inv.getProduct().getId(), inv);
        }
        inventoryRepository.saveAll(inventoryMap.values());

        // Bước 4 — Tạo Order entity
        // buildOrder() snapshot dữ liệu từ cart (giá, tên sản phẩm) vào order_items.
        //   — Lý do snapshot: giá sản phẩm có thể thay đổi sau này, order phải giữ giá gốc.
        // Order status khởi tạo = PENDING_PAYMENT
        Order order = buildOrder(userId, cart, request);
        orderRepository.save(order);

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
                        orderEventProducer.publishOrderCreated(order);
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

        List<OrderItem> items = cart.getItems().stream().map(e -> {
            OrderItem item = new OrderItem();
            item.setOrder(order);
            item.setProductId(e.getProductId());
            item.setProductName(e.getProductName());
            item.setProductImage(e.getImage());
            item.setQuantity(e.getQuantity());
            item.setUnitPrice(e.getPrice());
            item.setSubtotal(e.getPrice().multiply(BigDecimal.valueOf(e.getQuantity())));
            return item;
        }).toList();

        order.setItems(items);
        order.setSubtotal(cart.getTotal());
        order.setShippingFee(calculateShippingFee(cart));
        order.setDiscount(BigDecimal.ZERO);
        order.setTotal(order.getSubtotal().add(order.getShippingFee()));
        return order;
    }

    // Sinh mã đơn hàng: ORD-yyyyMMdd-xxxxxx (vd: ORD-20240115-001234).
    // RANDOM.nextInt(999999) có padding 6 số → unique trong 1 ngày là đủ (1M order/ngày).
    // Không dùng sequence DB: tránh phụ thuộc DB cho việc tạo mã hiển thị.
    private String generateOrderNumber() {
        return "ORD-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                + "-" + String.format("%06d", RANDOM.nextInt(999999));
    }

    // Miễn phí ship nếu tổng đơn >= 500k, ngược lại 30k.
    private BigDecimal calculateShippingFee(CartDto cart) {
        if (cart.getTotal().compareTo(new BigDecimal("500000")) >= 0) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal("30000");
    }

    // Tạo URL thanh toán — Phase 3 mock, trỏ thẳng đến PaymentController.
    // Phase 4+: sẽ thay bằng URL thật từ VNPAY/MOMO.
    private String generatePaymentUrl(Order order) {
        return "/api/v1/payments/confirm?orderId=" + order.getId();
    }

    // === GET USER ORDERS (READ-ONLY) ===
    // @Transactional(readOnly = true): hint cho Hibernate bỏ dirty checking, tăng tốc.
    // Phân trang + sort theo createdAt DESC — order mới nhất lên đầu.
    @Transactional(readOnly = true)
    public Page<OrderSummaryDto> getUserOrders(UUID userId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return orderRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable).map(OrderSummaryDto::from);
    }

    // === GET ORDER DETAIL (READ-ONLY) ===
    // Dùng findByIdWithItems() — LEFT JOIN FETCH để tránh N+1 query.
    // Kiểm tra userId để đảm bảo user chỉ xem được order của chính họ.
    // Nếu không match: throw OrderNotFoundException (không throw access denied vì không muốn leak thông tin).
    @Transactional(readOnly = true)
    public OrderDetailDto getOrderDetail(UUID orderId, UUID userId) {
        Order order = orderRepository.findByIdWithItems(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order not foun: " + orderId));

        if (!order.getUserId().equals(userId)) {
            throw new OrderNotFoundException("Order not foun: " + orderId);
        }

        return OrderDetailDto.from(order);
    }
}
