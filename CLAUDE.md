# used-trade — Claude Code 세션 가이드

> 새 세션 시작 시 자동으로 읽힘. 짧게 유지할 것.

---

## 프로젝트

중고거래 백엔드 포트폴리오. **Spring Boot 3.5 · Java 21 · MySQL 8 · Redis 7 · Docker · AWS**.

- 3주 단독 개발 (2026-04-25 ~ 2026-05-15) — **완료**
- 단순 CRUD 가 아니라 **ADR 6편 + Before/After 측정 + AWS 실배포**가 핵심 가치
- GitHub: https://github.com/Cansur/used-trade

---

## 🚧 현재 위치 — 새 세션이면 여기부터

- **단계**: 5-b 완료. 모든 도메인 + AWS 실배포 + Saga + README/문서 정리 끝. **면접 준비 단계**.
- **브랜치**: `main` (PR #1~#11 모두 머지 완료)
- **AWS**: cleanup 완료 — 평소 정지 상태. 시연 시 [docs/deploy/aws-setup.md](docs/deploy/aws-setup.md) Step 7+8 재실행 (~15분)
- **다음 작업 후보**:
  - (선택) ADR-7 **Outbox 패턴** — 부분 Saga 의 T2/크래시/보상-실패 자동화. ~1.5-2일.
  - (선택) 면접 직전 AWS 재배포
  - 그 외엔 면접 학습 — 아래 문서 참고

### 새 세션 용도별 진입

- **아키텍처 / 트레이드오프 Q&A**: `docs/adr/001~006` + `docs/guides/*.md` 읽고 시작. 각 문서 끝에 "면접 대비 Q&A" 섹션 있음.
- **코드 작업 / 새 기능**: 아래 컨벤션 + 패키지 구조 참고.
- **AWS 재배포**: `docs/deploy/aws-setup.md` + `/tmp/usedtrade-secrets.env` 의 시크릿 재사용.

---

## 문서 인덱스

### ADR — 왜 그렇게 선택했나
| # | 주제 | 파일 |
|---|---|---|
| 1 | 모듈러 모놀리스 (vs MSA) | [docs/adr/001](docs/adr/001-modular-monolith.md) |
| 2 | 낙관적 락 + Spring Retry | [docs/adr/002](docs/adr/002-optimistic-locking.md) |
| 3 | Redis Pub/Sub 멀티 인스턴스 채팅 | [docs/adr/003](docs/adr/003-redis-pubsub-chat.md) |
| 4 | 커서 페이징 | [docs/adr/004](docs/adr/004-cursor-pagination.md) |
| 5 | Saga Orchestration (부분 구현) | [docs/adr/005](docs/adr/005-saga-orchestration.md) |
| 6 | AWS 배포 스택 선택 | [docs/adr/006](docs/adr/006-aws-stack-choice.md) |

### Guide — 어떻게 작동하나
- [project-structure.md](docs/guides/project-structure.md) — 패키지 경계, 의존 규칙, DTO 패턴
- [testing-strategy.md](docs/guides/testing-strategy.md) — 138 테스트 분류, 도구 결정
- [docker.md](docs/guides/docker.md) — compose / Dockerfile 결정, ECR 워크플로우

### 배포
- [docs/deploy/aws-setup.md](docs/deploy/aws-setup.md) — AWS 배포 절차 (수동 CLI)

---

## 핵심 측정 결과 (면접 어필)

| 주제 | 측정 / 입증 |
|---|---|
| 동시성 제어 (ADR-2) | 동일 상품 N=50 동시 예약 — 중복 거래 3건 → 0건, p95 193ms |
| 커서 페이징 (ADR-4) | 깊은 페이지 OFFSET 14.40ms → CURSOR 0.71ms (20.1배) |
| 멀티 인스턴스 채팅 (ADR-3) | 두 JVM 간 Redis Pub/Sub 릴레이 — `ChatStompDualInstanceTest` 통과 |
| Saga 보상 (ADR-5) | PG 실패 → `trade.cancel` + Product 복원 — `TradeSagaCompensationIT` 통과 |
| AWS 실배포 | EC2 2대 (cross-AZ) + ALB + RDS + ElastiCache |
| 테스트 | 단위 + 통합 138 / 0 실패 |

> Saga 는 **부분 구현** — T1 결제 실패만 자동 보상, T2/크래시/보상-실패는 운영자 수동 (ADR-5 에 명시). 과장 금지.

---

## 진행 완료 (PR 히스토리)

| PR | 내용 |
|---|---|
| #1 | user 도메인 (가입/로그인/refresh/로그아웃) |
| #2 | product 도메인 (CRUD + 커서 페이징 + 이미지 Mock) |
| #3 | trade RESERVED + ADR-2 (낙관적 락 정량화) |
| #4 | chat 단일 서버 (REST + WebSocket/STOMP) |
| #5 | chat 다중 인스턴스 + Redis Pub/Sub + ADR-3 |
| #6 | AWS Stage 1 (Dockerization) + Stage 2 (실배포) |
| #7 | payment + Mock PG + Saga Orchestration |
| #8 | README + AWS 재배포 + 시연 캡처 (chat GIF + Postman + AWS 콘솔) |
| #9 | Saga 한계 명시화 (부분 Saga) |
| #10 | aws-setup.md Account ID / VPC ID redact |
| #11 | README 트림 (388→138줄) + ADR 3편 + Guide 3편 |

---

## 컨벤션

- **커밋**: Conventional Commits — `feat(user):`, `fix:`, `docs:`, `test:`, `build:`
- **브랜치**: GitHub Flow — `main` + `feature/<scope>`. PR 로 머지.
- **의존 방향**: 도메인 → common 만, common → 도메인 ❌. 도메인 간엔 ID(값) 만 참조.
- **Entity**: setter 미노출, 정적 팩토리 + 의도 메서드. `@Version` (trade/product).
- **DTO**: `record` + Bean Validation. 변환은 `from(Entity)` 정적 팩토리.
- **JPA**: `@Enumerated(EnumType.STRING)`, `open-in-view: false`.
- **테스트**: JUnit 5 + Mockito + AssertJ, `@DisplayName` 한글. 통합은 `@SpringBootTest`.
- **응답**: 모든 엔드포인트가 `ApiResponse<T>` 래핑. 예외는 `GlobalExceptionHandler` 중앙 처리.
- **ID 타입**: 모든 엔티티 `Long`.

자세히 — [docs/guides/project-structure.md](docs/guides/project-structure.md)

---

## 패키지 구조

```
com.portfolio.used_trade/
├── common/    cross-cutting (config, exception, response, base entity)
├── user/      회원 + 인증 (security/ 포함)
├── product/   상품 (CRUD + 커서 페이징 + 이미지 Mock)
├── trade/     거래 (낙관적 락 + Saga)
├── chat/      채팅 (STOMP + Redis Pub/Sub)
└── payment/   결제 (Mock PG)
```

각 도메인 내부: `controller / service / domain / repository / dto` (+ 필요 시 `config / pubsub / storage / gateway`)

---

## 자주 쓰는 명령

```bash
# 인프라 + 앱
docker compose up -d                           # MySQL :3307 + Redis :6379
./gradlew bootRun                              # 앱 :8080
curl http://localhost:8080/api/hello/health-db # {mysql:UP, redis:UP}

# 테스트
./gradlew test                                 # 단위 + 통합 (138)
./gradlew benchmark                            # ADR-4 커서 페이징 벤치

# Git
git log --oneline -5
gh pr list
gh run list --limit 3                          # CI 결과
```

---

## 새 세션 점검 순서

1. `git branch --show-current` → `main` 확인
2. 위 **"🚧 현재 위치"** 읽기
3. 용도 (Q&A / 코드 / 배포) 에 맞춰 문서 인덱스에서 해당 문서 진입
4. 컨벤션 / 설계 결정 헷갈리면 `docs/guides/` + `docs/adr/` 참고
