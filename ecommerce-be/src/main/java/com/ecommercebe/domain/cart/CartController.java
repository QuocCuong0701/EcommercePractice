package com.ecommercebe.domain.cart;

import com.ecommercebe.dto.AddToCartRequest;
import com.ecommercebe.dto.CartDto;
import com.ecommercebe.dto.UpdateCartRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    @GetMapping
    public ResponseEntity<CartDto> getCart(@RequestHeader("X-User-Id") UUID userId) {
        return ResponseEntity.ok(cartService.getCart(userId));
    }

    @PostMapping("/items")
    public ResponseEntity<CartDto> addItem(@RequestHeader("X-User-Id") UUID userId,
                                           @Valid @RequestBody AddToCartRequest request) {
        cartService.addItem(userId, request.getProductId(), request.getQuantity());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/items/{productId}")
    public ResponseEntity<CartDto> updateItem(@RequestHeader("X-User-Id") UUID userId, @PathVariable UUID productId,
                                              @Valid @RequestBody UpdateCartRequest request) {
        cartService.updateQuantity(userId, productId, request.getQuantity());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/items/{productId}")
    public ResponseEntity<CartDto> removeItem(@RequestHeader("X-User-Id") UUID userId, @PathVariable UUID productId) {
        cartService.removeItem(userId, productId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    public ResponseEntity<Void> clearCart(@RequestHeader("X-User-Id") UUID userId) {
        cartService.clearCart(userId);
        return ResponseEntity.noContent().build();
    }
}
