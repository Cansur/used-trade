# ADR-7 - 상품 등록 AI 보조

### 상태
- **Status** : Proposed (2026-10-30) — 초안. 측정 (MEAS-1, MEAS-5) 후 Accepted
- 요구사항 : [assist-spec.md](../specs/assist-spec.md)

<br>

## 배경

판매자가 제목·설명을 쓰면 카테고리와 가격 범위를 추천합니다.<br>
LLM 은 **느리고, 비싸고, 응답 형식이 불안정한 외부 의존성**입니다. 결제 PG 와 같은 부류로 다룹니다.

- AI 가 실패해도 상품 등록은 된다 — 추천은 보조
- 사용자 대기는 최대 3초 (동기 응답, assist-spec 2-1)
- LLM 은 텍스트에서 정보만 추출하고, 가격은 시스템이 DB 로 계산한다
- 호출 비용을 제한하고, 잘 되는지 측정할 수 있을 것

<br>

## 결론

- 새 도메인 `assist` — product 데이터는 product 가 노출한 조회 Service 로만 읽는다
- `LlmClientPort` 뒤에 Claude / Mock / Controllable 어댑터 (결제 `PaymentGatewayPort` 와 같은 패턴)
- 요청 하나에 3초 예산. 빠른 실패·형식 오류만 남은 예산 안에서 1회 재시도, 그 외는 폴백
- 폴백 = 키워드 규칙 카테고리 + DB 가격 통계. 응답에 `source` 로 출처 표시
- Redis 로 사용자별 호출 제한 + 같은 입력 캐싱
- 가격 범위에 표본 수 기반 신뢰도 표시

<br>

## 비교

### assist 위치

| | product 안 (`product.assist`) | 별도 도메인 `assist` |
|--|--|--|
| 응집 | 상품 등록 화면 기능이라 자연스러움 | 추천 로직이 한 곳에 모임 |
| product 영향 | LLM·Redis·Resilience4j 의존이 product 로 번짐 | product 는 조회 Service 2개만 노출 |
| 측정·장애 격리 | 상품 CRUD 와 섞임 | 지표·설정·테스트를 따로 관리 |

**product 안 선택 X** — product 는 이미 CRUD + 커서 페이징 + 이미지를 가진 핵심 도메인. 외부 의존성이 불안정한 보조 기능을 섞으면 핵심 도메인의 의존과 테스트가 무거워집니다.

### 결과 전달 — 동기 vs 비동기

assist-spec 2-1 참고. 사용자 체감은 같고 비동기는 결과 저장소·전달 경로만 늘어납니다. 대기 중 스레드 점유는 가상 스레드로 완화하고 MEAS 로 확인합니다.

### 재시도

| | 재시도 없음 | 고정 1회 | **예산 안 조건부 1회** |
|--|--|--|--|
| 최악 대기 | 3초 | 6초 | 3초 |
| 일시 오류 (429/5xx/연결) | 바로 폴백 | 회복 | 회복 (예산 남으면) |
| 타임아웃 | 폴백 | 대기만 2배 | 폴백 |

타임아웃 재시도가 무의미하다는 건 **가설** — MEAS-5 로 실측 후 확정합니다.

<br>

## 구현 방법

### 1. 패키지와 의존

```
assist/
├── controller/  AssistController         POST /api/assist/listing
├── service/     AssistService            흐름 조율 (제한 → 캐시 → LLM → 검증 → 폴백 → 가격)
│                KeywordCategoryClassifier 폴백 카테고리
├── llm/         LlmClientPort, ClaudeLlmClient, MockLlmClient
├── cache/       AssistCache, AssistRateLimiter (Redis)
└── dto/
```

| 방향 | 방법 |
|---|---|
| assist → product (카테고리 목록) | `CategoryQueryService.findActive()` — product 가 노출 |
| assist → product (가격 표본) | `ProductPriceQueryService.findPrices(categoryId, keywords)` — product 가 노출 |
| assist → product Repository 직접 | X — ADR-1 에러 상황 3 을 반복하지 않음 |

### 2. LlmClientPort

```java
public interface LlmClientPort {
    LlmResult extract(LlmRequest request, Duration timeout);
}

record LlmRequest(String title, String description, List<CategoryOption> categories) {}

sealed interface LlmResult {
    record Success(String categoryName, String brand, String condition, TokenUsage usage) implements LlmResult {}
    record Failure(FailureType type, String detail) implements LlmResult {}
}

enum FailureType { TIMEOUT, TRANSIENT, INVALID_RESPONSE }  // TRANSIENT = 연결 오류, 429, 5xx
```

- 실패를 **종류별로** 돌려줘야 서비스가 재시도 여부를 정할 수 있습니다 (결제 `Result` 는 성공/실패만 구분 — 여기선 부족)
- 어댑터는 "LLM 이 뭐라고 했나" 만 책임. 카테고리가 DB 활성 목록에 있는지 검증은 `AssistService` 가 합니다 (어댑터를 바꿔도 검증은 그대로)
- `ClaudeLlmClient` : Haiku 5.5, tool use 로 JSON 강제, **SDK 내장 재시도 끔** (`maxRetries = 0`, 예산 계산이 틀어지지 않게)
- `MockLlmClient` : 시연·로컬용, 키워드 규칙으로 결정적 응답
- `ControllableLlmClient` (test) : 지연, 타임아웃, 깨진 JSON, 없는 카테고리 주입

### 3. 요청 흐름

```
1. 호출 제한 확인 → 초과면 429 (LLM 호출 안 함)
2. 캐시 조회 → 있으면 source=CACHE
3. LLM 호출 (남은 예산을 timeout 으로)
4. 검증 실패 / TRANSIENT 이고 남은 예산 ≥ 1.5초 → 1회 재시도
5. 그래도 실패 → 폴백 카테고리, source=FALLBACK + fallbackReason
6. 카테고리가 정해지면 가격 통계 계산 (LLM 성공 여부와 무관)
```

### 4. 폴백

| 실패 | 카테고리 | 가격 |
|---|---|---|
| 타임아웃 / 오류 / 형식 오류 | `price-anchors.csv` 의 keywords 로 규칙 매칭 | DB 통계 |
| 규칙 매칭도 없음 | **추천 안 함** (`null`) — 틀린 추천보다 없는 게 낫다 | 없음 |
| Redis 장애 | LLM 호출 생략 → 폴백 (호출 제한을 확인할 수 없으면 비용 보호 우선) | DB 통계 |
| 서킷 열림 | LLM 호출 생략 → 폴백 (3초를 기다리지 않음) | DB 통계 |

서킷브레이커는 Resilience4j 를 검토합니다 (DEV-4). 장애가 계속될 때 매 요청이 3초씩 기다리는 걸 막는 용도입니다.

### 5. 가격 범위와 신뢰도

- 같은 카테고리 + 키워드가 겹치는 상품 가격을 모아 IQR 로 이상치 제거 → p25 / 중앙값 / p75
- 신뢰도 (값은 시드 생성 후 조정)
  - 표본 5개 미만 → 범위 대신 "참고할 매물 부족"
  - p75 / p25 > 2 → `LOW` (SPEC-4 조사에서 3배 초과 품목 다수 — 피규어, 디지털 피아노 등)
  - 그 외 → `NORMAL`

### 6. Redis 키

| 용도 | 키 | 값 | TTL |
|---|---|---|---|
| 호출 제한 | `assist:rate:{userId}:{epochMinute}` | INCR 카운트 | 60초 |
| 캐시 | `assist:cache:v1:{sha256(정규화한 제목 + 설명)}` | LLM 추출 결과 JSON | 24시간 |

- 호출 제한은 고정 윈도우 (분당 N회, N 은 DEV-7 에서 결정). 단순하고 설명하기 쉬움
  - 분이 키에 들어 있어 분이 바뀌면 새 키를 쓴다. 지난 키는 TTL 로 자동 삭제 (사용자당 동시에 최대 2개)
  - 약점 : 경계 몰아쓰기 (12:00:59 에 N회 + 12:01:00 에 N회). 목적이 비용 폭주 방지라 허용. 엄격해야 하면 슬라이딩 윈도우 (Sorted Set) / 토큰 버킷
  - `assist:rate:{userId}` 하나에 첫 요청 때 EXPIRE 를 거는 방식은 X — INCR 과 EXPIRE 사이 장애 시 TTL 없는 키가 남아 그 사용자가 영구 차단됨
- 캐시에는 **LLM 성공 결과만** 넣습니다. 폴백 결과를 넣으면 LLM 이 회복돼도 폴백이 계속 나갑니다
- 캐시에는 가격을 넣지 않습니다. 가격은 DB 가 바뀌면 달라지므로 매번 계산
- `v1` 은 프롬프트 버전. 프롬프트를 바꾸면 `v2` 로 올려 이전 캐시를 무효화
- 정규화 : 공백 정리, 소문자화. 입력 원문은 키에 넣지 않고 해시만 사용
- **완전 일치만 캐싱** (정규화 후). 유사 입력 캐싱 (임베딩 기반 시맨틱 캐시) 은 X — 임베딩 호출·벡터 검색 비용이 추가되고, "아이폰 15" 와 "아이폰 15 Pro" 처럼 문장은 비슷하지만 다른 상품에 틀린 적중이 난다
- 예상 : 매물마다 설명이 달라 적중률은 낮을 것 (재요청·되돌리기 정도). MEAS-3 에서 실측, 낮으면 낮다고 기록

### 7. 응답

```json
{
  "category": { "id": 1, "name": "전자기기" },
  "attributes": { "brand": "Apple", "condition": "사용감 보통" },
  "price": { "min": 700000, "median": 760000, "max": 820000, "sampleSize": 42, "confidence": "NORMAL" },
  "source": "LLM",
  "fallbackReason": null
}
```

### 8. 지표 (Micrometer)

- `assist.requests{source=LLM|CACHE|FALLBACK}` — 출처별 건수
- `assist.llm.latency` — LLM 호출 지연 (p50 / p95)
- `assist.llm.failures{type=TIMEOUT|TRANSIENT|INVALID_RESPONSE}`
- `assist.llm.tokens{direction=input|output}` — 건당 비용 계산용

### 9. 가상 스레드

- `spring.threads.virtual.enabled=true`
- 확인할 것 : HTTP 클라이언트가 `synchronized` 안에서 I/O 를 기다리면 JDK 21 에서 pinning 발생 → MEAS 에서 플랫폼 스레드와 비교해 효과가 실제로 나는지 확인

<br>

## 고려했으나 하지 않은 것

- **비동기 추천 (202 + 조회 / WebSocket push)** — 2-1 참고
- **LLM 이 가격까지 결정** — 근거 없는 숫자를 만들 수 있음. 가격은 DB 통계
- **중고 플랫폼 크롤링으로 가격 데이터 확보** — 약관 위반 + 데이터베이스권 침해 판단 사례 (네이버 부동산 vs 다윈중개 1심). 수작업 기준값 + 합성 데이터로 대체 (assist-spec 2-6)
- **이미지 입력** — 비용·지연 증가, 이미지 저장소가 Mock

<br>

## 열린 질문

- 서킷브레이커 임계값 (실패율, 열림 시간) — DEV-4
- 호출 제한 N — DEV-7
- 신뢰도 기준값 — 시드 생성 후 분포 보고 조정
- 타임아웃 재시도 포함 여부 — MEAS-5
- 가상 스레드 효과 — MEAS-1 과 함께 측정

<br>

## 참조

- [assist-spec.md](../specs/assist-spec.md) - 요청 명세
- [PaymentGatewayPort](../../src/main/java/com/portfolio/used_trade/payment/gateway/PaymentGatewayPort.java) - 같은 Port/Adapter 패턴
- [ADR-1](001-modular-monolith.md) - 의존 규칙, 에러 상황 3 (다른 도메인 Repository 직접 주입)
- [price-anchors.csv](../../src/main/resources/seed/price-anchors.csv) - 폴백 키워드 + 가격 기준값
