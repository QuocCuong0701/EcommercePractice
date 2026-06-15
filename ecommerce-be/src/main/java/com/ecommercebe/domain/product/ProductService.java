package com.ecommercebe.domain.product;

import com.ecommercebe.dto.ProductDetailDto;
import com.ecommercebe.dto.ProductSummaryDto;
import com.ecommercebe.dto.enumtype.ProductStatus;
import com.ecommercebe.exception.ProductNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProductService {

    private final ProductRepository productRepository;
    private final RedisTemplate<String, Object> redisTemplate;

    private static final String CACHE_PREFIX = "product:";
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    public Page<ProductSummaryDto> list(int page, int size, UUID categoryId, BigDecimal minPrice, BigDecimal maxPrice) {
        Pageable pageable = PageRequest.of(page, size);
        return productRepository.findWithFilters(categoryId, minPrice, maxPrice, pageable)
                .map(ProductSummaryDto::from);
    }

    public ProductDetailDto getBySlug(String slug) {
        String cacheKey = CACHE_PREFIX + slug;
        ProductDetailDto cached = (ProductDetailDto) redisTemplate.opsForValue().get(cacheKey);

        if (cached != null) return cached;

        ProductDetailDto dto = productRepository.findBySlug(slug)
                .map(ProductDetailDto::from)
                .orElseThrow(() -> new ProductNotFoundException("Product not found: " + slug));

        redisTemplate.opsForValue().set(cacheKey, dto, CACHE_TTL);
        return dto;
    }

    public Page<ProductSummaryDto> search(String query, int page, int size) {
        return productRepository.search(query, PageRequest.of(page, size)).map(ProductSummaryDto::from);
    }

    public void evictCache(String slug) {
        redisTemplate.delete(CACHE_PREFIX + slug);
    }

    public ProductDetailDto findById(UUID id) {
        Product product = productRepository.findByIdAndStatus(id, ProductStatus.ACTIVE)
                .orElseThrow(() -> new ProductNotFoundException("Sản phẩm không tồn tại: " + id));
        return ProductDetailDto.from(product);
    }
}
