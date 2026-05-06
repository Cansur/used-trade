# ADR-4 — 상품 목록 페이징: 커서 vs 오프셋

- **Status**: Accepted (2026-05-05)
- **Context**: Phase 2 / W1 Day 5 — 상품 목록(`GET /api/products`) 구현
- **Related**: ADR-2 (낙관적 락), 인덱스 `idx_products_status_id (status, id)`

---

## 결정

**커서 페이징** 채택. 정렬 키는 `id DESC`, 커서는 앞 페이지 마지막 id.

쿼리 형태:
```sql
SELECT * FROM products
 WHERE status = 'AVAILABLE'
   AND (:cursor IS NULL OR id < :cursor)
 ORDER BY id DESC
 LIMIT :size + 1;
```

`size + 1` 트릭으로 다음 페이지 존재 여부(`hasNext`) 를 정확히 판정.

---

## 옵션 비교

| 축 | 오프셋 `LIMIT/OFFSET` | 커서 `id < lastId` |
|---|---|---|
| 깊은 페이지 비용 | 버려질 행까지 모두 스캔 → `O(offset + size)` | 인덱스 시크 한 번 → `O(log n + size)` |
| 신규 INSERT 중 일관성 | 페이지 어긋남 가능 (중복/누락) | 안정 |
| 임의 페이지 점프 (예: 1000페이지) | 가능 | 불가 (다음/이전만) |
| 총 페이지 수 표시 | `COUNT(*)` 가능 | 별도 쿼리 필요 |
| 본 인덱스 활용 | 별도 정렬 필요할 수 있음 | `idx_products_status_id` 가 직접 지원 |
| UX 적합 | 페이지 번호 UI | 무한 스크롤 / 피드 |

본 프로젝트의 UX 는 **무한 스크롤 / 최신순 피드** 가정 → 커서가 자연.

---

## 측정 — Before/After

> 측정 환경: 로컬 Docker MySQL 8.0, 10만 건 AVAILABLE 상품, 50건/페이지, 워밍업 5회 + 100회 반복 p50.
>
> 실행: `./gradlew benchmark` (`CursorPagingBenchmarkTest`).

### 응답 시간 (p50, 워밍업 5회 + 측정 100회)

| 시나리오 | OFFSET | CURSOR | 속도비 |
|---|---|---|---|
| 1페이지 (`offset=0` / `cursor=null`) | 1.21 ms | 1.16 ms | 1.0x — 차이 없음 |
| 깊은 페이지 (`offset=99000` / `cursor=1001`) | 14.40 ms | 0.71 ms | **20.1x** |

### EXPLAIN 비교 (깊은 페이지)

**OFFSET 99000:**
```
type=ref           key=idx_products_status_id   key_len=1   ref=const
rows=49737         Extra=Backward index scan; Using index
```

**CURSOR `id < 1001`:**
```
type=range         key=idx_products_status_id   key_len=9   ref=null
rows=999           Extra=Using where; Backward index scan; Using index
```

### 핵심 해석

| 항목 | OFFSET | CURSOR | 의미 |
|---|---|---|---|
| `type` | `ref` | `range` | CURSOR 는 범위 시크. 더 정밀 |
| `key_len` | 1 byte | 9 bytes (`status` 1 + `id` 8) | CURSOR 는 인덱스의 status+id 둘 다 활용해서 좁힘 |
| `rows` (옵티마이저 추정) | 49,737 | 999 | CURSOR 가 ~50× 적게 스캔 |
| `Using index` | ✅ | ✅ | 둘 다 covering index — 테이블 row 접근 없이 인덱스만으로 응답 |

→ 인덱스가 같아도 **OFFSET 은 N행 훑은 뒤 N-50 을 버림**, **CURSOR 는 인덱스 시크 + 50건만 읽음**. 페이지가 깊어질수록 격차 비례 증가.

---

## 결과 요약

- 깊은 페이지에서 커서가 **20.1× 빠름** (14.40ms → 0.71ms, p50 기준).
- 1페이지에선 차이 미미 (1.21 vs 1.16) — 인덱스 시크 비용은 페이지 깊이와 무관.
- 같은 인덱스 `idx_products_status_id (status, id)` 를 두 쿼리 모두 사용하지만, OFFSET 은 스캔 후 버림, CURSOR 는 시크 후 즉시 컷 — 옵티마이저 추정 행 수 49,737 vs 999.
- 둘 다 covering index 라 테이블 row I/O 없이 인덱스만으로 답함 → CURSOR 의 절대 응답 시간이 1ms 미만.

---

## 트레이드오프 / 한계

- 임의 페이지 점프(예: 1000페이지로 직접 이동) 불가능. 본 시나리오는 피드형 UX 라 영향 없음.
- 동률 정렬 키는 `id DESC` 하나라 안전하지만, 향후 `priceASC` 같은 정렬이 필요해지면 복합 커서(`(price, id) < (lastPrice, lastId)`) 가 필요. 그때 ADR 갱신.
- 운영 모니터링: 깊은 cursor 가 들어왔을 때도 `rows ≈ size+1` 인지 EXPLAIN 으로 확인.

---

## 후속 작업

- [x] `./gradlew benchmark` 실행 + 본 문서 수치 채움 (2026-05-05).
- [ ] (선택) 복합 정렬이 필요해지면 별도 ADR 로 분기.
- [ ] Phase 3 마무리 시 `docs/adr/INDEX.md` 또는 통합 README 에서 본 ADR 인용.

---

## 재현 방법

```bash
docker compose up -d                         # MySQL 8 기동
./gradlew benchmark                          # 시드 + 측정 + EXPLAIN 출력
```

`build/reports/tests/benchmark/index.html` 에서 표준 출력 확인 가능. 시드는 idempotent — 두 번째 실행부터는 skip 되어 ~5초만 소요.
