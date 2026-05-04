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

- **단계**: W1 Day 5 진행 / **product 도메인** 진입 (Phase 2)
- **브랜치**: `feature/product-domain` (작업 중)
- **마지막 완료**: Product 엔티티 + ProductRepository + 상태 머신 (`AVAILABLE/TRADING/SOLD`) + `@Version` 자리. Product 단위 10건 추가 → 총 52 PASS. Docker MySQL 에 `products` 테이블 + FK(`seller_id→users`, `category_id→categories`) + 3 인덱스 검증 완료.
- **다음 PR**: `feature/product-domain` Draft PR (예정)

### 다음 작업 — product 도메인 (W1 Day 5)

큰 그림:
- Product 엔티티 + 카테고리 (Many-to-One)
- 상품 등록/수정/삭제/조회 API (`/api/products`)
- 검색 + 페이징 — **커서 페이징 vs 오프셋 페이징** ADR 후보
- S3 Presigned URL (S3 환경 구성은 W2)

진입 순서:
1. ✅ Category 엔티티 + 시드
2. ✅ Product 엔티티 + Repository (`@Version` 자리만, retry 는 trade 합류 시 활성화)
3. **← 여기부터** ProductService TDD (CRUD + 소유자 검증 + 상태 전이 호출)
4. ProductController + curl 검증
5. 목록 조회 + 커서 페이징 결정
6. 이미지 업로드는 Presigned URL DTO만 (실제 S3 통합은 다음 W에)

설계 결정:
- ✅ **카테고리 마스터** — DB 시드 채택 (CommandLineRunner + `existsByName` idempotent).
- ✅ **상태 머신** — `AVAILABLE → TRADING → SOLD` 의 도메인 메서드 가드 (`reserve / markSold / cancelReservation`). 잘못된 전이는 `BusinessException(PRODUCT_NOT_AVAILABLE)`. 실제 호출은 trade 도메인 합류 시.
- ✅ **`@Version`** — 필드만 박아두고 ProductService 단계는 retry 미적용. trade 도메인 합류 시 ADR-2 핵심으로 활성화.
- ✅ **가격 타입** — `Long` (KRW 원). 다국적 결제 들어가면 `BigDecimal` 로 이전.
- 미정 — **소유자 검증 위치**: 도메인은 `Product.isOwnedBy(userId)` 헬퍼만 노출. 검증 호출은 ProductService 진입 시 결정 (서비스 진입 직후가 유력).

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

#### 🚧 product 도메인 — 진행 중 (W1 Day 5)
- [x] Category 엔티티 + 시드 (`Category`, `CategoryRepository`, `CategoryDataInitializer` + 단위 3건). MySQL 적재 확인.
- [x] Product 엔티티 + ProductRepository + ProductStatus 상태 머신 + `@Version` 자리 + 단위 10건. FK 2종 + 인덱스 3종 검증.
- [ ] **← 여기부터** ProductService TDD (CRUD + 소유자 검증)
- [ ] ProductController + curl 검증
- [ ] 목록 조회 + 커서 페이징 결정 (ADR 후보)
- [ ] 이미지 업로드 DTO (S3 통합은 W2)

#### 이후 도메인 (예정)
- trade — **ADR-2 핵심** (낙관적 락 + Saga + Outbox, 부하 테스트)
- chat — **ADR-3 핵심** (WebSocket + Redis Pub/Sub)
- payment (Mock)

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
