# ADR-1 — 모듈러 모놀리스 (vs MSA)

- **Status**: Accepted (2026-04-25)
- **Context**: Phase 0 — 프로젝트 셋업
- **Related**: ADR-2 (낙관적 락), ADR-3 (Redis Pub/Sub), Saga (ADR-5)

---

## 결정

Spring Boot 단일 애플리케이션 안에서 도메인을 **패키지로 분리**한 모듈러 모놀리스로 시작.

- 5개 도메인 (`user / product / trade / chat / payment`) + cross-cutting `common`
- **의존 방향 단방향**: 도메인 → common 만 허용, common → 도메인 금지
- 도메인 간 참조는 ID (값) 로만, 다른 도메인 엔티티 직접 import 금지
- 모듈 분리 (Gradle subproject) 아닌 **패키지 분리** — 컴파일 시 의존 규칙은 코드 리뷰로 enforce

---

## Context — 무엇을 증명하려 했나

본 프로젝트의 진짜 목표는 **기능 구현이 아니라 트레이드오프 입증**:
- 동시성 충돌 → 낙관적 락 + Retry 가 정확히 1건만 통과시키는가?
- 멀티 인스턴스 환경 → 채팅 메시지가 일관되게 전달되는가?
- 분산 트랜잭션 → 결제 실패 시 거래가 안전하게 보상되는가?

이 가치는 **모놀리스 안에서도 충분히 입증 가능** — 동시성은 한 DB 의 row 잠금, 채팅 분산성은 같은 Redis 의 Pub/Sub, Saga 는 한 JVM 안의 여러 트랜잭션. 굳이 MSA 로 안 가도 됨.

---

## 옵션 비교

| 축 | 단일 모놀리스 (패키지 무경계) | **모듈러 모놀리스 (선택)** | MSA (서비스 분리) |
|---|---|---|---|
| 도메인 경계 명확성 | ❌ (의존성 그래프 spaghetti 위험) | ✅ (단방향 의존 + ID 참조 강제) | ✅ (네트워크 boundary) |
| 트랜잭션 관리 | 단순 (`@Transactional` 하나로 끝) | 단순 (한 DB) | **복잡** (Saga / Outbox / 2PC) |
| 운영 / 배포 복잡도 | 낮음 | 낮음 | 높음 (서비스마다 CI/CD / 모니터링) |
| 부하 분산 | 전체 인스턴스 복제만 가능 | 같음 | 서비스별 스케일 |
| 디버깅 / 흐름 추적 | 쉬움 (한 JVM, 한 trace) | 쉬움 | 어려움 (분산 트레이싱 필요) |
| 3주 일정 적합도 | OK 지만 추후 분리 어려움 | **○ — 분리 가능 구조 + 빠른 구현** | ✗ (분산 모놀리스 위험) |
| 면접 어필 가치 | 약함 ("그냥 만들었다") | **강함** ("의도된 트레이드오프") | 강함 (단, 구현 못 끝내면 무의미) |

**MSA 기각 이유**:
- 3주 단독 개발 안에서 MSA 를 "제대로" 끝내려면 인프라 (서비스 메시 / 분산 트레이싱 / 서비스 디스커버리) + 코드 (인증 토큰 전파 / 서비스 간 통신 / 보상 트랜잭션) 양쪽 다 해야 함 — 한쪽이라도 빠지면 **분산 모놀리스** 라는 안티패턴으로 귀결
- 본 프로젝트의 가치는 트레이드오프 입증인데, MSA 자체에 시간을 다 쓰면 정작 측정할 트레이드오프 (동시성 / Saga) 가 얕아짐

**단일 모놀리스 기각 이유**:
- 도메인 경계가 없으면 `User` 가 `ChatRoom` 을 직접 import, `Trade` 가 `Payment` 의 내부 메서드 호출 등 spaghetti 위험
- 추후 채팅 분리 같은 시나리오에서 의존성 풀이가 지옥
- "도메인 주도 설계 (DDD) 의 Bounded Context" 어필 못 함

---

## 의존 규칙 — 실제 코드에서 어떻게 enforce 되나

본 프로젝트는 컴파일 시점 도구 (ArchUnit 등) 를 안 쓰고 **코드 리뷰 + 패키지 위치 + import 점검** 으로 enforce.

### 규칙

| 방향 | 허용? | 예시 |
|---|---|---|
| 도메인 → common | ✅ | `trade.service` 가 `common.exception.BusinessException` 사용 |
| common → 도메인 | ❌ | `common` 안에서 `User` import 금지 |
| 도메인 A → 도메인 B (엔티티 직접) | ❌ | `chat.domain.ChatRoom` 에서 `User` 엔티티 필드 X |
| 도메인 A → 도메인 B (ID 값) | ✅ | `ChatRoom.buyerId: Long` (User ID 만 들고 있음) |
| 도메인 A → 도메인 B (Service 호출) | ⚠️ | Saga 같은 cross-domain 흐름은 별도 Saga 클래스에서 (`TradeSagaService`) |

### 예외 케이스

**SecurityConfig 가 `user.security` 패키지에 있는 이유**:
- 원래는 `common.config` 에 있을 법한데, `JwtAuthenticationFilter` 가 user 도메인 종속 (Refresh Token / 블랙리스트 / `User` 도메인 조회)
- 의존 방향 위반 회피 위해 user 안으로 이동
- 다른 도메인이 인증 빈 (PasswordEncoder, JWT) 을 필요로 하면 user 가 빈 노출 (`@Bean`) — 다른 도메인은 user 의 빈을 주입 받음

**Saga Service 가 `trade` 안에 있는 이유**:
- `TradeSagaService` 는 trade + payment 두 도메인을 조율 → 어디 두느냐 결정 필요
- trade 가 비즈니스 흐름의 owner (거래 확정이 핵심) → trade 도메인 안
- payment 는 SDK 처럼 호출만 됨 → 자기 도메인 안에서 자기 트랜잭션만 책임

---

## Consequences

### 얻은 것
- **빠른 구현 + 명확한 경계** — 3주 안에 5도메인 + ADR 4종 + AWS 실배포 가능
- **추후 분리 여지** — 채팅 부하가 핵심 병목으로 측정되면 `chat` 패키지를 별도 Spring Boot 앱으로 추출 + Redis Pub/Sub 채널 그대로 재사용 → MSA 진입 비용 낮음 (ADR-3 이 이미 cross-instance 시연됨)
- **디버깅 단순** — 한 JVM, 한 trace, 한 로그 파일. 분산 트레이싱 없이도 흐름 추적 가능
- **의존 규칙 = 면접 어필** — "패키지 의존 규칙 어떻게 강제했나" 질문에 답할 거리 (코드 리뷰 + import 점검 + DTO 경계)

### 대가
- **단일 JVM 장애 = 전체 다운** — ALB + 2 EC2 인스턴스로 가용성 확보 (한 인스턴스 죽어도 나머지 처리)
- **공유 DB** — 도메인별 DB 분리는 다음 단계 (스키마/연결 풀 분리 → 결국 MSA 의 첫 발자국)
- **배포 단위 = 전체** — chat 만 핫픽스 못 함. blue-green / canary 도 전체 단위
- **언어 / 프레임워크 락인** — Java + Spring 으로 통일 (MSA 였으면 Go / Python 섞을 수 있음)

### 언제 재검토하나
| 시나리오 | 대응 |
|---|---|
| 채팅 WebSocket 연결 수가 일반 API 인스턴스의 GC / 메모리 패턴을 의미있게 다르게 만들 때 | `chat` 모듈만 별도 인스턴스로 분리. Redis Pub/Sub 은 이미 cross-instance |
| 도메인별 팀 형성 (예: 결제 팀 / 거래 팀) | 팀 단위로 owner 분리 — Conway's Law 따라 서비스 분리 |
| 도메인별 SLO 가 갈라짐 (예: chat 은 5ms, trade 는 200ms) | 인스턴스 분리로 GC tuning 별도 |
| MySQL 의 connection pool / IOPS 가 한 도메인에 지배될 때 | 도메인별 DB → MSA 진입 |

---

## 면접 대비 — 자주 나오는 질문

### Q. "왜 MSA 안 했어요? 트렌드인데"

**A**: "MSA 의 진짜 가치는 **독립 배포 + 독립 스케일 + 독립 팀 운영**입니다. 본 프로젝트는 단독 개발 + 3주 일정 + 부하 측정 가능 수준 (~100 동시 요청) 이라 그 가치를 활용할 단계가 아닙니다. MSA 로 시작하면 인프라 (서비스 메시 / 트레이싱 / 디스커버리) 에 시간이 빨려가 정작 증명하려던 동시성 / Saga / 멀티 인스턴스 일관성 같은 트레이드오프를 측정 못 합니다. **모듈러 모놀리스 = 패키지 경계로 MSA 진입 비용을 낮춰둔 상태**. 추후 채팅 부하가 측정값에서 병목으로 나오면 chat 만 분리할 준비 — Redis Pub/Sub 이 이미 cross-instance 입증 (ADR-3) 이라 분리 시 코드 변경 거의 없음."

### Q. "도메인 경계 어떻게 보장했나요?"

**A**: "코드 리뷰 + import 점검으로. 컴파일 시점 도구 (ArchUnit) 까지는 안 갔습니다. 규칙은 세 가지:
1. `common` 은 도메인 import 금지 (cross-cutting 만)
2. 도메인 간 엔티티 직접 참조 금지 — ID (값) 만 들고 있음 (예: `ChatRoom.buyerId: Long`)
3. cross-domain 흐름은 별도 Service (Saga 클래스) 에 두고 어디 도메인이 소유하는지 명시

추후 도메인이 늘어나거나 팀이 커지면 ArchUnit 같은 컴파일 시점 enforce 가 필요. 본 프로젝트 규모에선 매번 PR 리뷰가 더 효과적이라 판단."

### Q. "단일 JVM 장애는 어떻게 대응했나요?"

**A**: "ALB + 2 EC2 인스턴스 구성. 한 인스턴스 다운 → ALB 의 health check (15초 간격, 3회 실패 시 unhealthy) 가 그 인스턴스 라우팅 제외. 채팅 메시지도 Redis Pub/Sub 으로 인스턴스 간 일관성 유지되니 사용자 입장에선 무중단 (단, 새 채팅 SUBSCRIBE 는 잠시 끊겼다 재연결). RDS 와 ElastiCache 는 Multi-AZ 가능 — 본 데모는 비용 절감으로 Single-AZ 지만 운영에선 Multi-AZ 전환만 하면 됨."

---

## References

- 모듈러 모놀리스 vs MSA 비교: Sam Newman "Monolith to Microservices" 1장
- DDD Bounded Context: Eric Evans "Domain-Driven Design" 14장
- 본 프로젝트의 cross-domain 흐름 예시: [TradeSagaService](../../src/main/java/com/portfolio/used_trade/trade/service/TradeSagaService.java)
