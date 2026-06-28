package com.ecommercebe.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CheckoutRequest {
    private String idempotencyKey;
    private ShippingAddressDto shippingAddress;
    private String notes;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ShippingAddressDto {
        private String fullName;
        private String phone;
        private String address;
        private String province;
        private String district;
    }
}
