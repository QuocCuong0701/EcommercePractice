package com.ecommercebe.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
public class AddToCartRequest {
    @NotNull
    private UUID productId;
    @NotBlank
    private String productName;
    private String image;
    @NotNull
    private BigDecimal price;
    @Min(1)
    private int quantity;
    private int maxQuantity;
}
