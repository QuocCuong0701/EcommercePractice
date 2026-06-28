package com.ecommercebe.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

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