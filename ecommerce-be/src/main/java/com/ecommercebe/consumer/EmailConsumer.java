package com.ecommercebe.consumer;

import com.ecommercebe.domain.category.CategoryRepository;
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
public class EmailConsumer {
    private final CategoryRepository categoryRepository;

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
    @KafkaListener(topics = OrderEventProducer.TOPIC, groupId = "email-service")
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
