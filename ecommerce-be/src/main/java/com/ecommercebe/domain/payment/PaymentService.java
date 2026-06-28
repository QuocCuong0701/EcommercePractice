package com.ecommercebe.domain.payment;

import com.ecommercebe.domain.order.Order;
import com.ecommercebe.domain.order.OrderRepository;
import com.ecommercebe.dto.PaymentConfirmRequest;
import com.ecommercebe.dto.PaymentConfirmResponse;
import com.ecommercebe.dto.enumtype.OrderStatus;
import com.ecommercebe.dto.enumtype.PaymentStatus;
import com.ecommercebe.exception.OrderNotFoundException;
import com.ecommercebe.exception.PaymentFailedException;
import com.ecommercebe.producer.PaymentEventProducer;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {
    // PaymentService: xử lý thanh toán (mock trong Phase 3).
    // Flow: nhận request → idempotency check → validate order → mock payment → update order → publish event.
    // Điểm quan trọng: payment và order status update nằm trong cùng 1 transaction.
    //   — Nếu payment save fail → order không bị update → nhất quán.

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentEventProducer paymentEventProducer;
    private final ObjectMapper objectMapper = new ObjectMapper();

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
            Optional<Payment> existing = paymentRepository.findByIdempotencyKey(request.getIdempotencyKey());
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
        Order order = orderRepository.findById(request.getOrderId())
                .orElseThrow(() -> new OrderNotFoundException("Order not found: " + request.getOrderId()));

        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            throw new PaymentFailedException("Order status is " + order.getStatus().name() + ", expected PENDING_PAYMENT");
        }

        // Bước 3 — Mock payment:
        // Phase 3: luôn success, không gọi gateway thật.
        // Tạo transactionId giả (MOCK-TXN-XXXXXXXX) để có trace.
        // metadata: lưu thông tin debug (mock=true, processedAt).
        // Phase 4+: thay bằng gọi VNPAY/MOMO API, xử lý callback.
        String transactionId = "MOCK-TXN" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

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

        paymentRepository.save(payment);

        // Bước 4 — Update order status:
        // PENDING_PAYMENT → PAID.
        // Sau step này, order không thể thanh toán lại (step 2 sẽ reject).
        order.setStatus(OrderStatus.PAID);
        orderRepository.save(order);

        // Bước 5 — Publish ORDER_PAID event sau khi DB commit:
        // Tương tự OrderService: dùng TransactionSynchronizationManager.
        //   — afterCommit() chỉ chạy nếu toàn bộ transaction (step 1-4) thành công.
        //   — Nếu bước 2 fail (sai status), transaction rollback, không publish event.
        // Consumer nhận event: InventoryConsumer (deduct) + EmailConsumer + AuditConsumer.
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        paymentEventProducer.publishPaymentConfirmed(order);
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
