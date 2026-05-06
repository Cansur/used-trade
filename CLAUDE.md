# used-trade — Claude Code 세션 가이드

> 이 파일은 새 세션 시작 시 자동으로 읽힘. 짧게 유지할 것.
> 큰 마일스톤마다 "현재 위치" / "다음 작업" / "진행상황" 만 갱신.

---

## 프로젝트

중고거래 백엔드 포트폴리오. **Spring Boot 3.5 · Java 21 · MySQL 8 · Redis 7 · Docker · AWS(예정)**.

- 2주 압축 플랜 (시작 2026-04-25 → 종료 2026-05-08)
- 단순 CRUD 가 아니라 **3 ADR + Before/After 성능 수치**가 핵심 가치
- GitHub: https://github.com/Cansur/used-trade

---

## 🚧 현재 위치 — 새 세션이면 여기부터 읽기

- **단계**: W1 Day 5 마감 / **W2 진입 직전** (trade 도메인 ADR-2)
- **브랜치**: `feature/product-domain` (Draft PR 진행 중) → 곧 머지 후 `feature/trade-domain` 신설 예정
- **마지막 완료**: 이미지 Presigned URL Mock — `PresignedUrlRequest/Response` DTO + `ImageStoragePort` (Hexagonal Port) + `MockImageStorage` (`@Profile("!prod")`) + `ProductImageService` (소유자/SOLD 가드 + UUID objectKey) + `POST /api/products/{id}/images/presign` + 단위 5건. curl 6/6 그린. 총 75 PASS. W2 에서 `S3ImageStorage` 어댑터만 추가하면 즉시 진짜 S3 통합.
- **다음 PR**: 현재 Draft PR 머지 후 `feature/trade-domain` 신설

### 다음 작업 — W2 trade 도메인 (ADR-2 핵심)

product 도메인 완전 마감. W2 진입 시 결정 포인트:

큰 그림 (W2):
- **trade**: 동시 예약 충돌 방어 — 낙관적 락(`@Version`) + Spring Retry, Saga + Outbox, k6/JMeter 부하 시뮬레이션 (ADR-2 핵심 어필)
- **chat**: WebSocket + Redis Pub/Sub (멀티 인스턴스 메시지 일관성, ADR-3 핵심)
- **payment**: Mock 결제 (Saga 트리거)
- **S3 통합**: `S3ImageStorage` 어댑터 추가 (인터페이스는 이미 박혀 있음 — AWS 가입 후 갈아끼우기만)
- **AWS 배포** (Phase 3): EC2 + ALB

진입 순서 제안 (W2 확정 시 갱신):
1. **← 여기부터** Trade 엔티티 + 상태 머신 (RESERVED → CONFIRMED → SETTLED / CANCELED)
2. TradeService — Product.reserve() 호출 + 낙관적 락 충돌 시 Spring Retry
3. 동시성 시뮬레이션 — k6 또는 JMeter 로 N개 동시 예약 → 1건만 성공 검증
4. (선택) Saga + Outbox 패턴 (payment 합류 시점)
5. ChatRoom + Message + WebSocket + Redis Pub/Sub
6. Payment Mock 어댑터
7. S3ImageStorage 추가 (AWS 가입 후)
8. AWS EC2 + ALB 배포

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

#### 🚧 trade 도메인 — 다음 (W2 진입)
- [ ] **← 여기부터** Trade 엔티티 + 상태 머신 + 낙관적 락 충돌 시뮬레이션 (ADR-2 핵심)
- [ ] Saga + Outbox (payment 합류)
- [ ] k6/JMeter 부하 시나리오 + Before/After 측정

#### 이후 도메인 (예정)
- chat — **ADR-3 핵심** (WebSocket + Redis Pub/Sub)
- payment (Mock)
- S3ImageStorage 어댑터 (AWS 가입 후)

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
| ADR-3 | Redis Pub/Sub 멀티서버 WebSocket | ALB 뒤 N대 인스턴스 메시지 일관성 |
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
