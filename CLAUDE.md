# used-trade — Claude Code 세션 가이드

> 이 파일은 새 세션 시작 시 자동으로 읽힘. 짧게 유지할 것.
> 큰 마일스톤마다 "현재 위치" / "다음 작업" / "진행상황" 만 갱신.

---

## 프로젝트

중고거래 백엔드 포트폴리오. **Spring Boot 3.5 · Java 21 · MySQL 8 · Redis 7 · Docker · AWS(예정)**.

- 3주 플랜 (시작 2026-04-25 → 종료 **2026-05-15**, 1주 연장)
- 단순 CRUD 가 아니라 **3 ADR + Before/After 성능 수치**가 핵심 가치
- GitHub: https://github.com/Cansur/used-trade

---

## 🚧 현재 위치 — 새 세션이면 여기부터 읽기

- **단계**: W3 / **모든 도메인 + AWS 배포 + Saga 완료** — 5-b 진입 (README 정리 + AWS 재배포 + 스크린샷 + chat GIF). 영상 X 결정.
- **브랜치**: `feature/readme-demo` (main 에서 분기. payment PR #7 머지 완료 `641441c`)
- **다음 작업** — **5-b A 옵션**:
  - **B1 README** (Claude) — 프로젝트 소개 + 아키텍처 다이어그램 + 기술 스택 표 + ADR 4종 링크 + Before/After 성능 표 + 한계/다음 단계 + 로컬 실행 가이드 (~1-1.5h)
  - **B2 AWS 재배포** (Claude) — `docs/deploy/aws-setup.md` 의 Step 7+8 재실행 (ALB / TG / Listener 재생성 + ElastiCache 재생성 + EC2/RDS start). `/tmp/usedtrade-secrets.env` 의 시크릿 (DB_PASSWORD / REDIS_PASSWORD / JWT_SECRET) 그대로 재사용 가능. ~10~15분.
  - **B3-1 스크린샷 5장** (사용자) — Claude 가 시나리오 + 캡처 화면 제시
  - **B3-2 chat GIF 1개** (사용자) — chat broadcast 두 브라우저 탭. Claude 가 시나리오 + 도구 (ScreenToGif/LICEcap) 가이드 제시
  - 영상 (mp4) 은 만들지 않음 — GIF + 스크린샷 + ADR + 코드로 충분히 어필

### 진입 순서 (전체 — 5/15 마감 기준)

1. ✅ Trade RESERVED + ADR-2 정량화 + PR #3 (`8e27d39`)
2. ✅ chat-1 + PR #4 (`56355ed`)
3. ✅ chat-2 + ADR-3 + PR #5 (`95aeb49`)
4. ✅ AWS Stage 1+2 + PR #6 (`f657f7a`)
5. ✅ payment + Mock PG + Saga Orchestration + PR #7 (`641441c`)
6. **← 여기부터** 5-b A — README + AWS 재배포 + 스크린샷 + chat GIF
7. (선택) Outbox 패턴 — 일정 여유 있으면

### 5-b 합의된 결정

- **영상 안 만듦** — 사고 깊이 (ADR + 통합 테스트) 가 이미 충분. 영상 대신 스크린샷 + GIF 로 가성비
- **시연 URL** — README 에 박되 면접 직전 재배포 안내 (24h 가동 ~$1)
- **GIF 핵심** — chat 의 cross-instance broadcast (ADR-3 가장 시각적)
- **스크린샷 후보** (Claude 가 시나리오 작성):
  1. 회원가입/로그인 응답
  2. 상품 등록 + 목록 (커서 페이징)
  3. 거래 예약 → confirm 응답 (CONFIRMED + PAID + gatewayTxId)
  4. 채팅 (두 사용자 메시지)
  5. AWS 콘솔 (ALB + EC2 2대 + RDS + Redis)

### 환경 / 시크릿

- AWS IAM: `usedtrade-admin` (us-east-1)
- ECR: 이미지 유지 (180MB, 무료)
- IAM Role / Instance Profile / Security Groups: 유지
- 비밀 (`/tmp/usedtrade-secrets.env`): `DB_PASSWORD`, `REDIS_PASSWORD`, `JWT_SECRET`. Bash 세션 시작 시 `source /tmp/usedtrade-secrets.env`
- Git Bash 환경: `export PATH="/c/Program Files/Amazon/AWSCLIV2:$PATH"` + `export MSYS_NO_PATHCONV=1`

### 새 세션 시작 명령 예시

```
"5-b A 진행. B1 README 부터 시작."
```
또는
```
"5-b B2 (AWS 재배포) 부터. README 는 다음에."
```

### 핵심 어필 카드 (면접용)

| 키워드 | 어디 | 입증 |
|---|---|---|
| 모듈러 모놀리스 (ADR-1) | 패키지 분리 | user/product/trade/chat/payment 도메인 분리 |
| 낙관적 락 + Spring Retry (ADR-2) | trade.reserve | N=20 동시 → 1건만 RESERVED, 중복 0 (Naive 비교 3건 → 0) |
| Redis Pub/Sub 멀티서버 (ADR-3) | chat | dual-instance STOMP 통합 테스트 통과 |
| 커서 페이징 (ADR-4) | product list | OFFSET 14ms vs CURSOR 0.7ms (10만건) |
| **Saga Orchestration** | trade.confirm | PG 실패 시 trade.cancel 보상 + Product 복원 통합 테스트 |
| AWS 매니지드 인프라 | EC2/RDS/ElastiCache/ALB | 실 배포 (시연 URL 가능) |

### 진입 순서 제안 (전체 일정 — 5/15 마감 기준)

1. ✅ Trade RESERVED + ADR-2 정량화 + PR #3 머지 (`8e27d39`)
2. ✅ chat-1 단일 서버 + PR #4 머지 (`56355ed`)
3. ✅ chat-2 — 다중 인스턴스 + Redis Pub/Sub + ADR-3 + PR #5 머지 (`95aeb49`)
4. ✅ AWS 배포 (Stage 1 Dockerization + Stage 2 인프라 + 데모 URL)
5. **← 여기부터** PR + cleanup → 다음 도메인:
   - **5-a payment + Saga** — confirm/settle/cancel 서비스 노출 + Mock PG + Outbox
   - **5-b README 최종 정리** — 시연 자료 + 데모 영상 + ADR 링크 정리
6. S3ImageStorage 어댑터 (AWS 사용 시)

### trade 도메인 진행 (RESERVED 1차)
1. ✅ TradeStatus enum (RESERVED → CONFIRMED → SETTLED / CANCELED)
2. ✅ Trade 엔티티 + 정적 팩토리 + 도메인 메서드 (confirm/settle/cancel) + `@Version`
3. ✅ TradeRepository
4. ✅ Spring Retry 의존성 + RetryConfig
5. ✅ TradeService.reserve() (`@Retryable` + `saveAndFlush` + `@Recover`)
6. ✅ TradeController (`POST /api/trades`, 201) + DTO 2종 (`TradeReserveRequest`, `TradeResponse`)
7. ⏭ curl 스모크 (A) → 동시성 통합 (B) → k6 (D) → confirm/settle/cancel 노출 (다음 PR)

설계 결정 (trade RESERVED 1차):
- ✅ **상태 머신** — `RESERVED → CONFIRMED → SETTLED / CANCELED`. 도메인 메서드 가드. 잘못된 전이는 `INVALID_TRADE_TRANSITION`. 이번 PR 의 서비스 노출은 `reserve` 만 — confirm/settle/cancel 은 도메인 메서드만 박아두고 다음 PR (payment 합류) 에서 서비스로 노출.
- ✅ **`Trade.reserve()` 정적 팩토리** — self-trade 차단 → `Product.reserve()` 호출 → 가격 스냅샷 → RESERVED 생성을 한 번에 묶음. Service 는 존재 검증과 영속화만.
- ✅ **가격 스냅샷 (`pricePaid`)** — Product.price 가 사후 변경되어도 거래 금액은 거래 시점 가격으로 고정. 일반적인 e-commerce 관행.
- ✅ **`cancel()` 범위** — RESERVED 단계만 허용. CONFIRMED 취소는 환불 로직과 함께 다음 PR. (사용자 합의)
- ✅ **`@Retryable` + `saveAndFlush`** — 보통의 `save()` 는 트랜잭션 커밋 시점에 flush → OptimisticLockingFailureException 이 메서드 밖에서 발생 → `@Retryable` 이 못 잡음. `saveAndFlush` 로 메서드 안에서 즉시 flush 강제 → 충돌이 메서드 안에서 터짐 → 재시도 가능. **이게 ADR-2 시연의 핵심.**
- ✅ **재시도 정책** — max=3, backoff 50ms × multiplier 2. 모두 실패 시 `@Recover` 가 `TRADE_ALREADY_RESERVED` 로 변환 — 사용자에겐 "다른 사람이 선점" 의미.
- ✅ **`@EnableRetry` 위치** — `trade/config/RetryConfig` (도메인 종속). 다른 도메인이 retry 를 요구하면 common/config 로 승격.
- ✅ **`buyerId` 출처** — 본문이 아닌 `@AuthenticationPrincipal AuthUser`. 본문 받으면 위장 예약 가능.

### product 도메인 진행 (전체 ✅)
1. ✅ Category 엔티티 + 시드
2. ✅ Product 엔티티 + Repository (`@Version` 자리만)
3. ✅ ProductService CRUD + 소유자/SOLD 가드
4. ✅ ProductController + curl 8 시나리오
5. ✅ 목록 + 커서 페이징 (ADR-4) — 깊은 페이지 20.1× 빠름 측정
6. ✅ 이미지 Presigned URL Mock (Port/Adapter 분리, W2 에 S3 어댑터만 추가)

설계 결정:
- ✅ **카테고리 마스터** — DB 시드 채택 (CommandLineRunner + `existsByName` idempotent).
- ✅ **상태 머신** — `AVAILABLE → TRADING → SOLD` 의 도메인 메서드 가드 (`reserve / markSold / cancelReservation`). 잘못된 전이는 `BusinessException(PRODUCT_NOT_AVAILABLE)`. 실제 호출은 trade 도메인 합류 시.
- ✅ **`@Version`** — 필드만 박아두고 ProductService 단계는 retry 미적용. trade 도메인 합류 시 ADR-2 핵심으로 활성화.
- ✅ **가격 타입** — `Long` (KRW 원). 다국적 결제 들어가면 `BigDecimal` 로 이전.
- ✅ **소유자 검증 위치** — 서비스 진입 직후 `loadAndCheckMutable(sellerId, productId)` 공통 가드. 컨트롤러는 `@AuthenticationPrincipal AuthUser` 의 `id()` 만 전달.
- ✅ **SOLD 가드** — 수정/삭제 모두 `PRODUCT_NOT_AVAILABLE` 로 거부 (거래 이력 보존). 별도 ErrorCode 신설하지 않고 재사용.
- ✅ **PATCH 의미** — `ProductUpdateRequest` 모든 필드 nullable. `null = 변경 없음`. 모든 필드 null 이면 무동작.
- ✅ **목록 페이징** — 커서 채택 (ADR-4 후보). 근거: 깊은 페이지에서 OFFSET 의 `O(n)` → 커서 + id 인덱스 시크 `O(log n)`. 인덱스 `idx_products_status_id (status, id)` 가 직접 지원. AVAILABLE 만 default, size 1~50 클램핑, size+1 트릭으로 hasNext 정확.
- ✅ **이미지 저장소 추상화** — `ImageStoragePort` 인터페이스 + `MockImageStorage` (Hexagonal Port/Adapter). W2 에서 `S3ImageStorage` 만 같은 인터페이스로 추가하면 도메인 코드 변경 없음. objectKey 형식 `products/{id}/{uuid}.{ext}` 로 충돌 방지 + 확장자 보존.

---

## 진행 상황

### ✅ Phase 1 — 환경 + 공통 + CI (완료, main 머지됨)
- JDK 21, Docker Compose (MySQL :3307 + Redis :6379)
- 도메인 패키지 구조 (common / user / product / trade / chat / payment)
- 공통 코드 (ApiResponse · ErrorCode · BusinessException · GlobalExceptionHandler · BaseEntity · SecurityConfig · HelloController)
- GitHub Actions CI (compile + test on push/PR to main/develop)
- 첫 커밋 `e4b42be chore: initial project scaffolding`

### 🚧 Phase 2 — 도메인 구현 (진행 중)

#### ✅ user 도메인 (완료, main 머지됨, PR #1 squash → `3ca087b`)
- 38 단위 테스트 PASS, Docker 환경 curl 7 시나리오 검증
- 체크포인트 1 (signup) / 2 (login + /me) / 3 (refresh + logout 멱등)
- 수동 검증으로 잡은 버그: 401/403 EntryPoint 누락, logout 멱등성 깨짐
- API: `POST /api/users` `POST /api/auth/{login,refresh,logout}` `GET /api/users/me`

#### ✅ product 도메인 — 완료 (W1 Day 5 마감)
- [x] Category 엔티티 + 시드 (`Category`, `CategoryRepository`, `CategoryDataInitializer` + 단위 3건). MySQL 적재 확인.
- [x] Product 엔티티 + ProductRepository + ProductStatus 상태 머신 + `@Version` 자리 + 단위 10건. FK 2종 + 인덱스 3종 검증.
- [x] ProductService (`register/findById/update/delete`) + DTO 3종 (`Register/Update/Response`) + 단위 12건. 소유자·SOLD·존재 가드.
- [x] ProductController (`POST/GET/PATCH/DELETE`) + SecurityConfig 패치 (GET permitAll) + curl 8 시나리오 검증.
- [x] B1: 커서 페이징 Repository(JPQL+JOIN FETCH) + DTO + Service.list + 단위 6건.
- [x] B2: `GET /api/products` Controller + curl 5/5 + 벤치마크 (10만 건, 깊은 페이지 20.1× 빠름). ADR-4 작성.
- [x] 이미지 Presigned URL Mock — Port/Adapter, ProductImageService, `POST /api/products/{id}/images/presign` + 단위 5건 + curl 6/6. 총 75 PASS.

#### ✅ trade 도메인 RESERVED 1차 + ADR-2 정량화 (W2)
- [x] Trade 엔티티 + 상태 머신 (RESERVED → CONFIRMED → SETTLED / CANCELED) + `@Version`
- [x] TradeService.reserve() — `@Retryable` + `saveAndFlush` + `@Recover(DataAccessException)` (ADR-2 핵심)
- [x] TradeController `POST /api/trades` (201)
- [x] curl 스모크 6/6 (A) + fix(BusinessException @Recover) — 401/INVALID/404/SELF/RESERVED/CONFLICT
- [x] 동시성 통합 N=20 (B) — success=1, failures={PRODUCT_NOT_AVAILABLE=19}, 중복 0
- [x] Naive 통합 N=20 (Before, ADR-2 시연) — 중복 거래 3건 발생 입증
- [x] Load 통합 N=50 (After 정량) — wall=246ms, p95=193ms, OK=1, BusinessException=49
- [x] `docs/adr/002-optimistic-locking.md`
- [x] 단위 19 + 통합 3 = 22 trade 테스트. 총 97 PASS.
- [ ] confirm/settle/cancel 서비스 노출 (다음 PR — payment 합류 시점)
- [ ] Saga + Outbox (payment 합류)

#### 🚧 chat 도메인 — chat-1 완료 (W2)
- [x] ChatRoom + Message + MessageType (TEXT만, SYSTEM_* 향후) + UNIQUE (product, buyer)
- [x] ChatRoomRepository + MessageRepository (커서 페이징 size+1 트릭)
- [x] ChatService — createOrGetRoom (중복 방 재사용) / listMyRooms (buyer/seller 합집합) / sendMessage (도메인 가드 위임) / listMessages (참여자 가드) / assertParticipant
- [x] REST: POST /api/chat/rooms, GET /api/chat/rooms, GET /{id}/messages
- [x] WebSocket/STOMP: /ws SockJS endpoint + /topic broker + /app SEND prefix
- [x] JwtChannelInterceptor — CONNECT 단계 JWT 검증 + SUBSCRIBE 단계 참여자 검증
- [x] ChatMessageController — @MessageMapping("/chat/rooms/{roomId}/messages") + SimpMessagingTemplate broadcast
- [x] SecurityConfig 갱신 — /ws/** permitAll (STOMP CONNECT 에서 JWT 검증)
- [x] 단위 21 (도메인 8 + 서비스 13) + REST curl 9/9. 총 118 PASS.
- [x] chat-2 — Redis Pub/Sub 릴레이 (publisher/subscriber/config) + `ChatMessageController` publisher 경로 전환
- [x] WebSocketConfig dual endpoint (`/ws` raw + `/ws-sockjs`)
- [x] STOMP 통합 테스트 — 단일 인스턴스 (`ChatStompSingleInstanceTest`) + 다중 인스턴스 (`ChatStompDualInstanceTest`, `SpringApplicationBuilder` 로 인스턴스 B 시동, 같은 Redis 공유)
- [x] `docs/adr/003-redis-pubsub-chat.md` (Sticky / Pub/Sub / Kafka / Hazelcast 비교, Before/After 표, Redis 단일 장애점 한계 명시)
- [x] 단위 21 + 통합 STOMP 2 = chat 23 + 기존 trade/product/user 97 = **총 120 PASS**

#### 이후 도메인 (예정)
- payment (Mock + Saga + Outbox)
- S3ImageStorage 어댑터 (AWS 가입 후)
- AWS EC2 + ALB 배포

### 📋 Phase 3 — 마무리
- 테스트 커버리지 80% (JaCoCo)
- ADR 문서화 (docs/adr/*.md)
- AWS EC2 + ALB 배포

---

## 핵심 설계 결정

| # | 결정 | 근거 |
|---|---|---|
| ADR-1 | Modular Monolith (not MSA) | 2주 내 구현 가능, 도메인 경계 패키지로 분리 |
| ADR-2 | 낙관적 락 + `@Version` + Spring Retry | 중고거래 충돌 빈도 낮음, DB 락 대기 제거 |
| ADR-3 | Redis Pub/Sub 멀티서버 WebSocket | ALB 뒤 N대 인스턴스 메시지 일관성. [docs/adr/003](docs/adr/003-redis-pubsub-chat.md) — STOMP dual-instance 통합 테스트로 입증 |
| ADR-4 | 상품 목록 커서 페이징 | OFFSET 14.40ms vs CURSOR 0.71ms (깊은 페이지, 10만 건). [docs/adr/004](docs/adr/004-cursor-pagination.md) |
| 인증 | 자체 JWT (vs OAuth) | Security 필터/BCrypt 직접 구현 = 학습/시연 가치 |
| Access | 30분 stateless | DB 조회 없는 검증 |
| Refresh | Redis 14일 | 강제 로그아웃 / 세션 통제 |
| 로그아웃 | `blacklist:<jti>` Redis SET (TTL = 토큰 잔여시간) | stateless 한계 보완 |

---

## 컨벤션

- **커밋**: Conventional Commits — `feat(user):`, `chore:`, `fix:`, `docs:`, `test:`
- **브랜치**: GitHub Flow — `main` + `feature/<scope>`. Draft PR 로 진행 가시화.
- **의존 방향**: 도메인 → common 만, common → 도메인 ❌
- **Entity**: setter 미노출, 정적 팩토리 + 의도 메서드 (`changeNickname()`)
- **DTO**: record + Bean Validation. 변환은 `from(Entity)` 정적 팩토리
- **JPA**: `@Enumerated(EnumType.STRING)` 강제, `open-in-view: false`
- **테스트**: Mockito + AssertJ, `@DisplayName` 한글 의도 서술
- **응답 포맷**: 모든 엔드포인트가 `ApiResponse<T>` 래핑

---

## 패키지 구조

```
com.portfolio.used_trade/
├── common/           cross-cutting (config, exception, response, base entity)
├── user/             ← 진행 중 (회원가입 완료, JWT 진행 중)
├── product/          다음
├── trade/            Phase 2 핵심 — ADR-2
├── chat/             Phase 2 핵심 — ADR-3
└── payment/          Mock
```

각 도메인 내부: `controller / service / domain / repository / dto`

---

## 자주 쓰는 명령

```bash
# 인프라 기동
docker compose up -d
docker compose ps                              # mysql/redis healthy 확인

# 앱 실행 (포그라운드)
./gradlew bootRun

# 동작 확인
curl http://localhost:8080/api/hello/health-db   # {mysql:UP, redis:UP}

# 단위 테스트
./gradlew test

# 특정 테스트만
./gradlew test --tests "com.portfolio.used_trade.user.service.UserServiceTest"

# DB 직접 확인
docker exec usedtrade-mysql mysql -u usedtrade_user -pusedtrade1234! -D usedtrade -e "SELECT * FROM users\G"

# Git 작업
git status
git log --oneline -5
gh pr view 1                                   # Draft PR 상태
gh run list --limit 3                          # CI 결과
```

---

## 새 세션을 열었을 때 점검 순서

1. `git branch --show-current` → `feature/user-domain` 인지 확인
2. `docker compose ps` → 두 컨테이너 모두 healthy 인지
3. 위 **"🚧 현재 위치"** 의 "다음 작업" 부터 진입
4. 컨벤션·설계 결정에서 헷갈리면 본 문서 참고
