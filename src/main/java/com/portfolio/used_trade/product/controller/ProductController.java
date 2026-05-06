package com.portfolio.used_trade.product.controller;

import com.portfolio.used_trade.common.response.ApiResponse;
import com.portfolio.used_trade.product.dto.ProductCursorPageResponse;
import com.portfolio.used_trade.product.dto.ProductRegisterRequest;
import com.portfolio.used_trade.product.dto.ProductResponse;
import com.portfolio.used_trade.product.dto.ProductUpdateRequest;
import com.portfolio.used_trade.product.service.ProductService;
import com.portfolio.used_trade.user.security.AuthUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 상품 관련 REST API.
 *
 * <p>현재 노출 API:
 * <ul>
 *   <li>POST   /api/products       — 상품 등록 (JWT 인증 필요)</li>
 *   <li>GET    /api/products/{id}  — 단건 조회 (permitAll)</li>
 *   <li>PATCH  /api/products/{id}  — 부분 수정 (인증 + 소유자)</li>
 *   <li>DELETE /api/products/{id}  — 삭제 (인증 + 소유자)</li>
 * </ul>
 *
 * <p>목록 조회({@code GET /api/products}) 는 다음 단계에서 추가 — 커서 페이징 결정 후.
 *
 * <p><b>응답 포맷</b>
 * <ul>
 *   <li>POST → 201 Created + {@link ApiResponse} 래핑</li>
 *   <li>GET / PATCH → 200 OK + {@link ApiResponse} 래핑</li>
 *   <li>DELETE → 204 No Content (본문 없음)</li>
 * </ul>
 *
 * <p>도메인 예외(소유자 검증 실패, 상품 없음 등)는 {@code GlobalExceptionHandler}
 * 가 ErrorCode → HTTP 상태 + ApiResponse 로 일괄 변환한다.
 */
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    /**
     * 상품 등록.
     *
     * <p>{@link Valid} — Bean Validation 위반 시 400 INVALID_INPUT 으로 변환.
     * 사용자 식별은 {@link AuthUser#id()} (토큰 클레임에서 미리 만들어진 principal).
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProductResponse> register(
            @AuthenticationPrincipal AuthUser auth,
            @Valid @RequestBody ProductRegisterRequest request
    ) {
        return ApiResponse.success(productService.register(auth.id(), request));
    }

    /**
     * 단건 조회 (인증 불필요 — 비로그인 사용자도 상품 페이지 진입 가능).
     */
    @GetMapping("/{id}")
    public ApiResponse<ProductResponse> findById(@PathVariable("id") Long id) {
        return ApiResponse.success(productService.findById(id));
    }

    /**
     * AVAILABLE 상품 목록 (커서 페이징, 인증 불필요).
     *
     * <p>쿼리 파라미터:
     * <ul>
     *   <li>{@code cursor}     — 앞 페이지 마지막 id (생략 = 첫 페이지)</li>
     *   <li>{@code categoryId} — 카테고리 필터 (생략 = 전체)</li>
     *   <li>{@code sellerId}   — 판매자 필터 (생략 = 전체)</li>
     *   <li>{@code size}       — 페이지 크기, default 20, 1~50 으로 클램핑</li>
     * </ul>
     *
     * <p>예: {@code GET /api/products?categoryId=1&size=20}<br>
     * 다음 페이지: {@code GET /api/products?categoryId=1&cursor=23&size=20}
     */
    @GetMapping
    public ApiResponse<ProductCursorPageResponse> list(
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long sellerId,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.success(productService.list(cursor, categoryId, sellerId, size));
    }

    /**
     * 부분 수정. 본인 상품만 가능, SOLD 상태는 거부.
     * 변경하지 않을 필드는 요청에서 생략(null).
     */
    @PatchMapping("/{id}")
    public ApiResponse<ProductResponse> update(
            @AuthenticationPrincipal AuthUser auth,
            @PathVariable("id") Long id,
            @Valid @RequestBody ProductUpdateRequest request
    ) {
        return ApiResponse.success(productService.update(auth.id(), id, request));
    }

    /**
     * 삭제. 본인 상품만 가능, SOLD 상태는 거부.
     *
     * <p>{@link HttpStatus#NO_CONTENT} (204) — REST 표준. 본문 없음 → 반환 타입 void.
     * 거래 이력 보존이 필요해지면 hard delete → soft delete 로 이전 (다음 단계).
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @AuthenticationPrincipal AuthUser auth,
            @PathVariable("id") Long id
    ) {
        productService.delete(auth.id(), id);
    }
}
