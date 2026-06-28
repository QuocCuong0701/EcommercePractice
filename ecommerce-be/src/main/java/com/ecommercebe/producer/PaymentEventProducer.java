package com.ecommercebe.producer;

import com.ecommercebe.domain.order.Order;
import com.ecommercebe.dto.OrderEvent;
import com.ecommercebe.dto.enumtype.EventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentEventProducer {

    // Cùng KafkaTemplate và TOPIC với OrderEventProducer — vì đều là order events.
    // Tách riêng class để phân tách trách nhiệm:
    //   - OrderEventProducer: gửi event từ OrderService (tạo/hủy order)
    //   - PaymentEventProducer: gửi event từ PaymentService (kết quả thanh toán)
    // Dù cùng TOPIC nhưng logic build event khác nhau, tách class giúp dễ maintain.
    private final KafkaTemplate<String, Object> kafkaTemplate;

    // Gửi event khi payment được xác nhận thành công (status = SUCCESS).
    // Được gọi từ PaymentService.confirmPayment() sau khi DB commit (afterCommit).
    // eventType = "ORDER_PAID" — cùng event type với OrderEventProducer.publishOrderPaid().
    // Consumer: InventoryConsumer (deductInventory) + EmailConsumer + AuditConsumer.
    // Lý do dùng eventType "ORDER_PAID" thay vì "PAYMENT_CONFIRMED":
    //   - Consumer chỉ cần switch trên 1 event type, không cần xử lý 2 event riêng.
    //   - "ORDER_PAID" phản ánh trạng thái business (order đã paid) chứ không phải hành động kỹ thuật.
    public void publishPaymentConfirmed(Order order) {
        OrderEvent event = OrderEvent.builder()
                .eventType(EventType.ORDER_PAID.name())
                .orderId(order.getId())
                .orderNumber(order.getOrderNumber())
                .userId(order.getUserId())
                .total(order.getTotal())
                .items(order.getItems().stream().map(i -> OrderEvent.OrderItemEvent.builder()
                        .productId(i.getProductId())
                        .productName(i.getProductName())
                        .quantity(i.getQuantity())
                        .unitPrice(i.getUnitPrice())
                        .build()).toList())
                .timestamp(Instant.now())
                .build();

        // Dùng whenComplete() để log lỗi async — không throw exception ra ngoài.
        // Nếu Kafka down, exception chỉ được log, không rollback transaction.
        // Lý do: transaction DB đã commit, không thể rollback để gửi lại.
        // Giải pháp cho production: Outbox Pattern (Phase 5+).
        kafkaTemplate.send(OrderEventProducer.TOPIC, order.getId().toString(), event)
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
                .eventType(EventType.PAYMENT_FAILED.name())
                .orderId(orderId)
                .reason(reason)
                .timestamp(Instant.now())
                .build();

        kafkaTemplate.send(OrderEventProducer.TOPIC, orderId.toString(), event);
    }
}
