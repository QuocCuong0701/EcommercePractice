package com.ecommercebe.domain.cart;

import com.ecommercebe.domain.product.ProductService;
import com.ecommercebe.dto.CartDto;
import com.ecommercebe.dto.CartItemDto;
import com.ecommercebe.dto.ProductDetailDto;
import com.ecommercebe.exception.CartItemNotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class CartService {

    private final ProductService productService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Duration CART_TTL = Duration.ofDays(7);

    private String cartKey(UUID userId) {
        return "cart:" + userId;
    }

    public CartDto getCart(UUID userId) {
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(cartKey(userId));
        List<CartItemDto> items = entries.values().stream()
                .map(e -> parseItem((String) e))
                .filter(Objects::nonNull).toList();

        BigDecimal total = items.stream()
                .map(e -> e.getPrice().multiply(BigDecimal.valueOf(e.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new CartDto(items, total, items.size());
    }

    private CartItemDto parseItem(String json) {
        try {
            return objectMapper.readValue(json, CartItemDto.class);
        } catch (Exception e) {
            log.error("Failed to parse cart item: {}", json, e);
            throw new RuntimeException("Invalid cart data");
        }
    }

    private String toJson(Object object) {
        try {
            return objectMapper.writeValueAsString(object);
        } catch (Exception e) {
            throw new RuntimeException("Serialization error", e);
        }
    }

    public String addItem(UUID userId, UUID productId, int quantity) {
        ProductDetailDto product = productService.findById(productId);

        String key = cartKey(userId);
        String productIdStr = productId.toString();

        String existing = (String) redisTemplate.opsForHash().get(key, productIdStr);
        int currentQty = existing != null ? parseItem(existing).getQuantity() : 0;

        CartItemDto item = CartItemDto.builder()
                .productId(productId)
                .productName(product.getName())
                .image(product.getImages().get(0))
                .price(product.getPrice())
                .quantity(currentQty + quantity)
                .build();

        String json = toJson(item);
        redisTemplate.opsForHash().put(key, productIdStr, json);
        redisTemplate.expire(key, CART_TTL);
        return json;
    }

    public void updateQuantity(UUID userId, UUID productId, int quantity) {
        if (quantity <= 0) {
            removeItem(userId, productId);
            return;
        }

        String key = cartKey(userId);
        String existing = (String) redisTemplate.opsForHash().get(key, productId.toString());
        if (existing == null) {
            throw new CartItemNotFoundException("Item not in cart");
        }

        CartItemDto item = parseItem(existing);
        item.setQuantity(quantity);
        redisTemplate.opsForHash().put(key, productId.toString(), toJson(item));
        redisTemplate.expire(key, CART_TTL);
    }

    public void removeItem(UUID userId, UUID productId) {
        redisTemplate.opsForHash().delete(cartKey(userId), productId.toString());
    }

    public void clearCart(UUID userId) {
        redisTemplate.delete(cartKey(userId));
    }
}
