package com.ecommercebe.domain.order;

import com.ecommercebe.dto.CheckoutRequest;
import com.ecommercebe.dto.CreateOrderResponse;
import com.ecommercebe.dto.OrderDetailDto;
import com.ecommercebe.dto.OrderSummaryDto;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
@Slf4j
public class OrderController {

    private final OrderService orderService;

    @PostMapping("/checkout")
    public ResponseEntity<CreateOrderResponse> checkout(
            @AuthenticationPrincipal UUID userId,
            @Valid @RequestBody CheckoutRequest request) {
        CreateOrderResponse response = orderService.checkout(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<Page<OrderSummaryDto>> getOrders(
            @AuthenticationPrincipal UUID userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(orderService.getUserOrders(userId, page, size));
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderDetailDto> getOrderDetail(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID id) {
        return ResponseEntity.ok(orderService.getOrderDetail(id, userId));
    }
}
