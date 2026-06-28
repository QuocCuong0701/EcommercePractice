package com.ecommercebe.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
