package com.ecommercebe.dto;

import com.ecommercebe.domain.product.Product;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ProductSummaryDto {

    private UUID id;
    private String name;
    private String slug;
    private BigDecimal price;
    private BigDecimal originalPrice;
    private String image;           // ảnh đầu tiên
    private String categoryName;
    private String status;

    public static ProductSummaryDto from(Product product) {
        return new ProductSummaryDto(
                product.getId(),
                product.getName(),
                product.getSlug(),
                product.getPrice(),
                product.getOriginalPrice(),
                product.getImages() != null && !product.getImages().isEmpty()
                        ? product.getImages().get(0) : null,
                product.getCategory() != null
                        ? product.getCategory().getName() : null,
                product.getStatus().name()
        );
    }
}