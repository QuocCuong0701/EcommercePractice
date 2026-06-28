package com.ecommercebe.dto;

import com.ecommercebe.dto.enumtype.PaymentMethod;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentConfirmRequest {
    @NotNull
    private UUID orderId;

    @NotNull
    private PaymentMethod paymentMethod;

    private String idempotencyKey;
}
