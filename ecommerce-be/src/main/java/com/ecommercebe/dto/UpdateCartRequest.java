package com.ecommercebe.dto;

import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class UpdateCartRequest {
    @Min(0)
    private int quantity;
}
