package com.ecommercebe.dto;

import com.ecommercebe.domain.order.Order;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

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
