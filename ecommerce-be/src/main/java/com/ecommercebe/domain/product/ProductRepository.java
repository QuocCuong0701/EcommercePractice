package com.ecommercebe.domain.product;

import com.ecommercebe.dto.enumtype.ProductStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

public interface ProductRepository extends JpaRepository<Product, UUID> {

    // Phân trang — KHÔNG dùng findAll() trả toàn bộ
    Page<Product> findByStatusOrderByCreatedAtDesc(
            ProductStatus status, Pageable pageable);

    Optional<Product> findBySlug(String slug);

    Optional<Product> findByIdAndStatus(UUID id, ProductStatus status);

    // Lọc theo category + price range
    @Query("""
            SELECT p FROM Product p
            WHERE p.status = 'ACTIVE'
            AND (:categoryId IS NULL OR p.category.id = :categoryId)
            AND (:minPrice IS NULL OR p.price >= :minPrice)
            AND (:maxPrice IS NULL OR p.price <= :maxPrice)
            ORDER BY p.createdAt DESC
            """)
    Page<Product> findWithFilters(
            @Param("categoryId") UUID categoryId,
            @Param("minPrice") BigDecimal minPrice,
            @Param("maxPrice") BigDecimal maxPrice,
            Pageable pageable
    );

    // Full-text search đơn giản với PostgreSQL ILIKE
    // (Phase 4 sẽ thay bằng Elasticsearch)
    @Query("SELECT p FROM Product p WHERE p.status = 'ACTIVE' " +
            "AND (LOWER(p.name) LIKE LOWER(CONCAT('%', :q, '%')) " +
            "OR LOWER(p.description) LIKE LOWER(CONCAT('%', :q, '%')))")
    Page<Product> search(@Param("q") String query, Pageable pageable);
}