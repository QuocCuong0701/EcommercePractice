package com.ecommercebe.consumer;

import com.ecommercebe.domain.inventory.Inventory;
import com.ecommercebe.domain.inventory.InventoryRepository;
import com.ecommercebe.dto.OrderEvent;
import com.ecommercebe.producer.OrderEventProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
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

    private final InventoryRepository inventoryRepository;

    // @Transactional: mỗi lần handle là 1 DB transaction riêng.
    // Nếu handle fail (exception) → không ack → Kafka retry → sau max retry → DLQ.
    // Lý do @Transactional ở consumer: đảm bảo DB update và Kafka ack đồng bộ.
    //   - Nếu ack trước DB save: crash sau ack → mất event.
    //   - Nếu DB save trước ack: crash trước ack → retry → idempotent.
    @KafkaListener(topics = OrderEventProducer.TOPIC, groupId = "inventory-service")
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
    private void deductInventory(OrderEvent event) {
        List<UUID> productIds = event.getItems().stream().map(OrderEvent.OrderItemEvent::getProductId).toList();
        Map<UUID, OrderEvent.OrderItemEvent> itemMap = event.getItems().stream()
                .collect(Collectors.toMap(OrderEvent.OrderItemEvent::getProductId, Function.identity()));

        List<Inventory> inventories = inventoryRepository.findAllByProductIdInWithLock(productIds);
        inventories.forEach(inv -> {
            OrderEvent.OrderItemEvent item = itemMap.get(inv.getProduct().getId());
            inv.setQuantity(inv.getQuantity() - item.getQuantity());
            inv.setReserved(inv.getReserved() - item.getQuantity());

            log.info("Inventory deducted: product={}, qty={}, remaining={}",
                    item.getProductId(), item.getQuantity(), inv.getQuantity());
        });
        inventoryRepository.saveAll(inventories);
    }

    // releaseReservation: gọi khi ORDER_CANCELLED — giải phóng hàng đã reserve.
    //   - reserved: giảm (không cần giữ chỗ nữa).
    //   - quantity: không đổi (hàng chưa bán, chỉ là hết reserved).
    // Đây là rollback của bước reserve trong checkout.
    // Lưu ý: nếu payment timeout (30 phút), scheduler publish ORDER_CANCELLED,
    // consumer này releaseReservation để hàng có sẵn cho người mua khác.
    private void releaseReservation(OrderEvent event) {
        List<UUID> productIds = event.getItems().stream().map(OrderEvent.OrderItemEvent::getProductId).toList();
        Map<UUID, OrderEvent.OrderItemEvent> itemMap = event.getItems().stream()
                .collect(Collectors.toMap(OrderEvent.OrderItemEvent::getProductId, Function.identity()));

        List<Inventory> inventories = inventoryRepository.findAllByProductIdInWithLock(productIds);
        inventories.forEach(inv -> {
            OrderEvent.OrderItemEvent item = itemMap.get(inv.getProduct().getId());
            inv.setReserved(inv.getReserved() - item.getQuantity());

            log.info("Reservation released: product={}, qty={}, reserved={}",
                    item.getProductId(), item.getQuantity(), inv.getReserved());
        });
        inventoryRepository.saveAll(inventories);
    }
}
