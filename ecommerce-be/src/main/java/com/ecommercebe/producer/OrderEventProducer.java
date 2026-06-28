package com.ecommercebe.producer;

import com.ecommercebe.domain.order.Order;
import com.ecommercebe.dto.OrderEvent;
import com.ecommercebe.dto.enumtype.EventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrderEventProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    public static final String TOPIC = "ecommerce.order.events";

    /**
     * Gửi event khi order vừa được tạo (status = PENDING_PAYMENT)
     * Consumer: EmailConsumer (gửi email xác nhận đơn hàng)
     */
    public void publishOrderCreated(Order order) {
        OrderEvent event = buildEvent(EventType.ORDER_CREATED.name(), order, null);
        kafkaTemplate.send(TOPIC, order.getId().toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish ORDER_CREATED for order={}: {}", order.getId(), ex.getMessage());
                    } else {
                        log.info("ORDER_CREATED published: order={} offset={}", order.getId(), result.getRecordMetadata());
                    }
                });
    }

    // Gửi event sau khi payment được xác nhận (status = PAID)
    // Consumer: InventoryConsumer (trừ inventory thật) + EmailConsumer (gửi email xác nhận thanh toán) + AuditConsumer
    public void publishOrderPaid(Order order) {
        OrderEvent event = buildEvent(EventType.ORDER_PAID.name(), order, null);
        kafkaTemplate.send(TOPIC, order.getId().toString(), event);
    }

    // Gửi event khi order bị hủy (timeout hoặc user hủy)
    // Consumer: InventoryConsumer (release reservation) + EmailConsumer + AuditConsumer
    // reason: lý do hủy ("Payment timeout" từ scheduler, hoặc "Cancelled by user")
    public void publishOrderCancelled(Order order, String reason) {
        OrderEvent event = buildEvent(EventType.ORDER_CANCELLED.name(), order, reason);
        kafkaTemplate.send(TOPIC, order.getId().toString(), event);
    }


    // đảm bảo consumer luôn thấy dữ liệu đúng tại thời điểm event xảy ra
    private OrderEvent buildEvent(String eventType, Order order, String reason) {
        return OrderEvent.builder()
                .eventType(eventType)
                .orderId(order.getId())
                .orderNumber(order.getOrderNumber())
                .userId(order.getUserId())
                .total(order.getTotal())
                .reason(reason)
                .items(order.getItems().stream().map(i -> OrderEvent.OrderItemEvent.builder()
                        .productId(i.getProductId())
                        .productName(i.getProductName())
                        .quantity(i.getQuantity())
                        .unitPrice(i.getUnitPrice())
                        .build()).toList())
                .timestamp(Instant.now())
                .build();
    }

}
