package com.ecommercebe.consumer;

import com.ecommercebe.dto.OrderEvent;
import com.ecommercebe.producer.OrderEventProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
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
    @KafkaListener(topics = OrderEventProducer.TOPIC, groupId = "audit-service")
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
