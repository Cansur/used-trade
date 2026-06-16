# ADR-4 - 커서페이징


### 상태

- **Status**: Accepted (2026-05-05)

## 배경

상품 목록 조회(`GET /api/products`)는 무한 스크롤 피드 UX로 노출됩니다.<br>
사용자가 스크롤을 내릴수록 뒤에 있는 페이지를 요청합니다.

일반적인 오프셋 방식 페이징은 페이지가 뒤로갈수록 응답 시간이 비례해서 느려집니다.
N번째 페이지를 조회하려면 DB가 앞의 `N×size` 행을 먼저 스캔하고 버린 뒤 size개만
반환하기 때문입니다.<br>
뒤에 있는 데이터 일수록 비용이 증가합니다.

- OFFSET이 큰 페이지에서도 일관된 응답 시간
- 무한 스크롤 UX에 적합한 페이징 방식
- 정렬 키(`id`)에 대한 기존 인덱스를 효율적으로 활용

<br>

## 결론

상품 목록 조회의 페이징을 `커서 페이징`으로 구현

- 정렬 키 `id DESC`, 커서는 앞 페이지의 마지막 id (`WHERE id < :cursor`)
- 임의 페이지 점프 없음 (다음/이전만)

## 비교

|  | 커서 페이징 | 오프셋 페이징 |
|---|---|---|
| OFFSET이 큰 페이지 비용 | `O(log n + size)` - 일정 | `O(offset + size)` - 비례 증가 |
| 임의 페이지 점프 | 불가 (다음/이전만) | 가능 |
| UX 적합 | 무한 스크롤·피드 | 페이지 번호 UI |

**오프셋 페이징 선택 X**
- 무한 스크롤 피드로 페이지 번호 점프가 필요 없음
- 데이터가 늘어날수록 일관된 응답 시간 보장 불가

<br>

## 구현 방법

### 1. 쿼리

```java
@Query(
    SELECT p FROM Product p
     WHERE p.status = :status
       AND (:cursor IS NULL OR p.id < :cursor)
     ORDER BY p.id DESC
)
List<Product> findCursorPage(
    @Param("status") ProductStatus status,
    @Param("cursor") Long cursor,
    Pageable pageable
);
```

- `:cursor IS NULL` 분기 - 첫 페이지와 다음 페이지를 같은 쿼리로 처리
- `LIMIT size + 1` - size+1개 받아 `hasNext` 판정

### 측정 결과

| 시나리오 | OFFSET | CURSOR | 개선 |
|---|---|---|---|
| 1페이지 | 1.21ms | 1.16ms | 1.0x (차이 없음) |
| 후반 페이지 (cursor=1001) | 14.40ms | 0.71ms | **20.1x** |

- 10만 건 환경, p50 기준, 100회 반복
- 기존 인덱스 `idx_products_status_id` 의 covering index 사용으로 테이블 row I/O 없음

<br>

## 에러 상황

### 1. 임의 페이지 점프 불가

상황 : 커서 페이징은 `다음 / 이전` 만 가능. `1000페이지로 바로 이동` 같은 점프 불가

이유 : 무한 스크롤 피드 가정으로 번호 점프 X

상태 : 이후 점프 필요 할 시 부분 오프셋 또는 별도 쿼리 적용

### 2. 복합 정렬 미지원

상황 : `id`정렬만 지원. 다른 정렬(가격, 수정시간)은 적용 불가

이유 : 아직 까지는 단일 정렬로 충분

상태 : 이후 필요할 시 정렬 옵션 및 새 인덱스 추가

<br>

## 참조

- [ProductRepository](../../src/main/java/com/portfolio/used_trade/product/repository/ProductRepository.java) — 커서 페이징 쿼리 (`@Query`)
- [Product](../../src/main/java/com/portfolio/used_trade/product/domain/Product.java) — 인덱스 `idx_products_status_id` 선언 위치
- [ProductCursorPageResponse](../../src/main/java/com/portfolio/used_trade/product/dto/ProductCursorPageResponse.java) — 커서 페이지 응답 형식 (items + nextCursor + hasNext)
- [CursorPagingBenchmarkTest](../../src/test/java/com/portfolio/used_trade/product/bench/CursorPagingBenchmarkTest.java) — Before/After 정량 측정 (10만 건, p50, 100회)