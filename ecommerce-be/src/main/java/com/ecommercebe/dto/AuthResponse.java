package com.ecommercebe.dto;

import lombok.Value;

@Value
public class AuthResponse {
    String accessToken;
    String refreshToken;
    String email;
}
