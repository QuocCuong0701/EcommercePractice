package com.ecommercebe.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@AllArgsConstructor
@Builder
public class CartDto {
    private List<CartItemDto> items;
    private BigDecimal total;
    private int itemCount;
}