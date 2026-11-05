# ADR-1 - 모듈러 모놀리스

### 상태
- **Status** : Accepted (2026-04-25)

<br>

## 배경

회원·상품·거래·채팅·결제 도메인으로 이루어진 중고거래 백엔드입니다.<br>
3주 개인 프로젝트로 진행하며, 동시성·분산·Saga 설계 트레이드오프를 측정하고 AWS 배포까지가 목표입니다.

- 도메인 간 경계를 명확하게 분리할 것
- 인프라 부담을 최소화할 것
- 트레이드오프 측정을 간편하게 할 수 있을 것

<br>

## 결론

Spring Boot 단일 애플리케이션 안에서 도메인을 패키지로 분리한 모듈러 모놀리스

- 5개 도메인 (user · product · trade · chat · payment) + cross-cutting common
- 의존 방향 : 도메인 -> common (양방향 X, 단방향 O)

<br>

## 비교
<br>

| |단일 모놀리스|모듈러 모놀리스|MSA|
|--|--|--|--|
|도메인 경계|없음|패키지 + 단방향 의존|네트워크 단위|
|인프라 부담|낮음|낮음|높음 (디스커버리·트레이싱·메시)|
|트랜잭션 / 디버깅|단순 (한 DB · 한 trace)|단순 (한 DB · 한 trace)|복잡 (Saga · 분산 트레이싱)|
|3주 일정 적합도|추후 분리 어려움|분리 가능 구조|분산 모놀리스 위험|

<br>

단일 모놀리스 선택 X
- 도메인 경계가 없어 `User`가 `ChatRoom`을 직접 import하는 식의 의존 얽힘 위험
- 추후 분리 시 의존성 풀이 비용이 큼

MSA 선택 X
- 3주 안에 인프라(디스커버리·트레이싱·메시)와 코드(인증 전파·서비스 간 통신·보상) 양쪽을 끝내기 어렵고, 한쪽이 빠지면 분산 모놀리스 안티패턴으로 귀결

<br>

## 구현 방법


### 의존 허용 표

| 방향 | 허용 | 비고 |
|---|---|---|
| 도메인 → common | O | |
| common → 도메인 | X |common이 특정 도메인 알면 cross-cutting 아니게 됨|
| 도메인 A → 도메인 B (Repository 직접 주입) | X |조회·검증 규칙이 소유 도메인 밖으로 새어 나감 — 에러 상황 3|
| 도메인 A → 도메인 B (Service 호출, @Bean) | O |cross-domain 의존을 한 곳에 모음. 엔티티가 필요하면 `UserService.getUser`, `ProductService.getProduct`|
| 도메인 A → 도메인 B (JPA 엔티티 연관 `@ManyToOne`) | △ 허용 |현재 코드의 현실 — 에러 상황 4. 신규 도메인 (assist 등) 은 ID 값만|
| 도메인 A → 도메인 B (ID 값) | O |분리·이전 시 영향 적음|



<br>

## 에러 상황

### 1. SecurityConfig 위치

상황 : `SecurityConfig`를 `common.config`패키지에 저장하려 했습니다.

문제 : `SecurityConfig`가 `JwtAuthenticationFilter`를 주입 받고 그 필터는 `user`도메인에 종속되어 있습니다. 그래서 `common`패키지에 두면 `common -> 도메인`에 위배됩니다.

결론 :  `SecurityConfig`를 `user.security`패키지로 이동 했고 다른 도메인에서 인증 유틸(PasswordEncoder)을 필요로 하면 `@Bean`으로 노출시킵니다.

### 2. TradeSagaService 위치

상황 : Saga 클래스를 어느 패키지에 둘 지 결정 해야합니다.

비교 :
- trade : `trade`가 거래 흐름의 비즈니스 owner (O)
- payment : `payment`는 SDK처럼 호출만 됨 (X)
- common : `trade`, `payment` 의존 (X)

결론 : ~~`trade.service.TradeSagaService`에 두었습니다.~~ → **변경 (2026-11-05)** `saga.service.TradeSagaService` 로 이동

- 문제 : 조율자가 `trade` 안에 있어 `trade → payment` 가 생기고, `Payment → Trade` 연관과 합쳐 패키지 순환이 됨. 순환 금지 규칙을 넣자 실패해서 발견 (trade 쪽 클래스 2개, payment 쪽 클래스 2개)
- 비교 : 이 순환만 규칙 예외로 두기 (순환이 남음) / 규칙을 넣지 않기 (새 순환을 못 잡음) / `saga` 패키지로 분리 (채택)
- 변경 : `saga → trade, payment`, `payment → trade` 한 방향. 확정 API 는 `TradeSagaController` 로 옮겼고 URL 은 그대로
- 함께 해결 : Saga 의 거래 조회를 `TradeService.getTradeOfBuyer` 로 옮김. 기존 `loadAndValidateBuyer` 는 자기 호출이라 `@Transactional` 이 적용되지 않았고, 이제 프록시를 거쳐 적용됨
- 검증 : 순환 규칙이 이동 전 실패, 이동 후 통과. 전체 테스트 통과. 실제 앱에서 `POST /api/trades/{id}/confirm` 호출 시 토큰 없음 401, 판매자 403, 구매자 200 (CONFIRMED + PAID), 없는 거래 404
- 남은 것 : `saga` 는 `Trade` 엔티티를 그대로 받아 `PaymentService.charge(Trade)` 에 넘김. 패키지 순환은 없지만 엔티티 의존은 에러 상황 4 의 결정대로 유지

### 3. TradeService에 다른 도메인 Repository 주입

상황 : `TradeService`가 다른 도메인의 `Service`가 아닌 `Repository`를 직접 주입

이유 : `ProductService`가 `Product DTO`만 돌려주면 `@Version` 충돌 검출이 안됩니다.

결론 : ~~나중에 해결해야할 문제~~ → **해결 (2026-10-30)**

- 같은 위반이 4곳 — `ProductService`·`TradeService`·`ChatService`·`TradeServiceNaive` 가 `UserRepository` / `ProductRepository` 직접 주입
- 소유 도메인에 조회 메서드 추가 : `UserService.getUser(id)`, `UserService.verifyExists(id)`, `ProductService.getProduct(id)` — NOT_FOUND 처리도 소유 도메인이 책임
- `@Version` 충돌 검출은 그대로 — `getProduct` 가 호출자의 쓰기 트랜잭션에 참여해 같은 영속성 컨텍스트의 엔티티를 돌려주기 때문. 동시성 테스트 (N=50, ADR-2) 통과로 확인
- `TradeServiceNaive` 의 products 직접 SQL 은 유지 — ADR-2 Before 측정용으로 일부러 가드를 우회하는 비교 코드
- **상태 변경도 소유 도메인으로** — 처음엔 조회만 고쳤고, `Trade` 엔티티가 여전히 `product.reserve()` / `markSold()` / `cancelReservation()` 을 직접 호출하고 있었음 (trade 가 상품 상태를 바꿈). `ProductService.reserve` / `markSold` / `cancelReservation` 으로 옮기고 `TradeService` 가 같은 트랜잭션에서 호출
  - 막혔던 이유는 "Service 는 DTO 만 돌려준다" 는 잘못된 전제. 기본 전파 (REQUIRED) 로 같은 트랜잭션에 합류하면 상태를 trade 에서 바꾸든 product 에서 바꾸든 `saveAndFlush` 시점에 함께 flush 되어 `@Version` 검사도 같다
  - `TradeService.reserve` 순서 : 본인 상품 차단 (`Trade.reserve`) → `productService.reserve` → `saveAndFlush`. 본인 상품이면 상품 상태를 건드리기 전에 끝남
  - 검증 : 동시성 (N=20), 부하 (N=50), Naive, Saga 보상 IT 통과
  - 비용 : "RESERVED 거래 = TRADING 상품" 을 예전엔 `Trade.reserve` 하나가 지켰지만, 이제는 `TradeService` 의 호출 순서가 지킴. 호출을 빠뜨리면 컴파일이 막아주지 않음 — 단위 테스트의 호출 순서 검증으로 보완
  - 전파 강제 : 세 메서드에 `Propagation.MANDATORY` — 트랜잭션 밖 호출은 `IllegalTransactionStateException`. `TradeProductTransactionIT` 로 이것과 롤백 원자성을 검증
- 테스트 : 138 → 155 (조회 3 + 상태 전이 7 + 취소 위임 2 + 트랜잭션 IT 2 + ArchUnit 4 − 도메인 테스트 이동 1), 실패 0
- 재발 방지 : `ArchitectureTest` (ArchUnit) 로 Repository 규칙을 테스트에 강제. 엔티티 연관이 허용된 기존 도메인의 "다른 도메인 엔티티 상태 변경" 은 ArchUnit 으로 잡기 어려워 코드 리뷰 대상

### 4. 문서와 코드의 불일치 — 엔티티 연관

상황 : 위 표와 Guide 는 "도메인 간 엔티티 직접 참조 금지, ID 만" 이라고 적었는데, 실제 코드는 `@ManyToOne` 으로 다른 도메인 엔티티를 들고 있었습니다 (7곳).

```
Product → User      Trade → Product, User      ChatRoom → Product, User
Message → User      Payment → Trade
```

비교 :
- ID 로 전환 : 규칙과 일치. 대신 엔티티 6개 + JPQL 조인 + DTO + 테스트 다수 수정, ADR-2·4·5 측정 재수행 필요
- 연관 유지 + 문서 수정 : 조회·변경 경로만 소유 Service 로 강제 (에러 상황 3). 엔티티 간 컴파일 의존과 LAZY 로딩 경로는 남음

결론 : 연관 유지, 문서를 현실에 맞게 수정. 신규 도메인 (`assist`) 부터는 ID 값만 참조. ID 전환은 도메인을 실제로 분리할 필요가 생길 때 진행

교훈 : 설계 규칙을 문서로만 두면 코드와 어긋나도 모른다 → `ArchitectureTest` (ArchUnit) 로 계층·도메인 경계·common·assist 규칙 4개를 테스트로 강제 (2026-10-31), 패키지 순환 금지 규칙 추가 (2026-11-05, 에러 상황 2)

<br>

## 참조

- [TradeSagaService](../../src/main/java/com/portfolio/used_trade/saga/service/TradeSagaService.java) - 에러 상황 2, 도메인 간 흐름의 조율자
- [SecurityConfig](../../src/main/java/com/portfolio/used_trade/user/security/SecurityConfig.java) - 에러 상황 1
- [TradeService](../../src/main/java/com/portfolio/used_trade/trade/service/TradeService.java) - 에러 상황 3
- [README — 아키텍처](../../README.md#아키텍처) - 도메인 의존 다이어그램