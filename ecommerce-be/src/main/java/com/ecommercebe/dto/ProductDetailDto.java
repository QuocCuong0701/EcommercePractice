package com.ecommercebe.dto;

import com.ecommercebe.domain.product.Product;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Value
public class ProductDetailDto {
    UUID id;
    String name;
    String slug;
    String description;
    BigDecimal price;
    BigDecimal originalPrice;
    List<String> images;
    String categoryName;
    String categorySlug;
    String status;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;

    public static ProductDetailDto from(Product product) {
        return new ProductDetailDto(
                product.getId(),
                product.getName(),
                product.getSlug(),
                product.getDescription(),
                product.getPrice(),
                product.getOriginalPrice(),
                product.getImages(),
                product.getCategory() != null ? product.getCategory().getName() : null,
                product.getCategory() != null ? product.getCategory().getSlug() : null,
                product.getStatus().name(),
                product.getCreatedAt(),
                product.getUpdatedAt()
        );
    }
}