# ADR-5 — Saga Orchestration (부분 구현)

- **Status**: Accepted (2026-05-09)
- **Context**: Phase 2 / W2 — payment 도메인 합류 + 거래 확정 플로우
- **Related**: ADR-1 (모놀리스 안에서도 분산 트랜잭션 필요), ADR-2 (낙관적 락은 단일 row, Saga 는 cross-domain)

---

## 결정

거래 확정 (`trade.confirm`) 을 **Saga Orchestration** 패턴으로 구현. 

- 조율자: `TradeSagaService` (trade 도메인 소유)
- 단계: T0 validate → T1 payment.charge → (PAID) T2 trade.confirm / (FAILED) T1' trade.cancel
- **각 단계가 독립 트랜잭션** — Saga 메서드 자체에 `@Transactional` 없음
- 보상은 T1 실패 한 가지만 자동화. T2 실패 / 크래시 / 보상 자체 실패는 **운영자 수동** + 향후 Outbox 합류로 자동화 예정

---

## Context — 왜 Saga 가 필요한가

거래 확정 = 두 도메인에 걸친 상태 전이:
1. **결제**: PG 에 돈 청구 → Payment row 생성 (PAID 또는 FAILED)
2. **거래**: Trade.status RESERVED → CONFIRMED, Product.status TRADING → SOLD

같은 DB 의 두 트랜잭션이라 "그냥 한 `@Transactional` 로 묶으면 되지 않나?" 싶지만 **안 됨**. 이유:

### 왜 단일 `@Transactional` 못 묶나
- PG 호출은 **외부 시스템** — HTTP 요청 → 응답 받는 동안 우리 DB 트랜잭션 열어두면 connection 점유 시간이 PG latency 만큼 늘어남 (HikariCP pool 고갈 위험)
- **PG 가 진짜 돈을 받았는데 우리 DB 만 롤백** 되면 데이터 불일치 (이중장부) — 사용자에겐 결제됐는데 거래가 없음
- 결제 결과 (PAID/FAILED) **자체가 영속화 가치 있는 정보** — 롤백되어 사라지면 환불 / 감사 / 재시도 근거 없음

→ **Saga 의 본질**: 각 단계를 독립 트랜잭션으로 쪼개고, 실패 시 **보상 (compensation) 트랜잭션** 으로 되돌리기.

---

## 옵션 비교 — Choreography vs Orchestration

| 축 | Choreography | **Orchestration (선택)** |
|---|---|---|
| 흐름 표현 | 이벤트 cascade — 각 서비스가 이벤트 발행/구독 | 중앙 조율자가 단계 호출 |
| 흐름 추적 | 어려움 (어떤 이벤트가 어떤 보상 trigger?) | 쉬움 (한 클래스 안에 흐름 보임) |
| 결합도 | 낮음 (이벤트 형식만 공유) | 중 (조율자가 단계 알아야) |
| 디버깅 | 어려움 (분산 트레이싱 필수) | 쉬움 (단일 stack trace) |
| 확장성 | 매우 강함 (새 구독자 추가만) | 조율자 갱신 필요 |
| 모놀리스 환경 적합도 | 과잉 (이벤트 broker 추가 필요) | **○ — 자연** |
| 면접 시연 | "어떤 이벤트가 발화되는지 보여드릴게요" → 트레이싱 화면 | "한 클래스 보여드리면 흐름 끝" |

**Orchestration 선택 이유**:
- 본 프로젝트는 모놀리스 — Choreography 의 가치 (낮은 결합) 가 작음
- 거래 도메인이 흐름의 **비즈니스 주체** — Trade 가 "내가 시작했고 내가 끝낸다" 가 자연스러움
- 디버깅 / 면접 시연 / 가르치기 모두 Orchestration 이 우월

---

## Saga 흐름 — 다이어그램

```
  buyer ──POST /api/trades/{id}/confirm──► TradeSagaService.confirm
                                              │
                                       T0 validate (read-only)
                                              │ (buyer 본인 + RESERVED 상태)
                                              ▼
                                       T1 paymentService.charge()
                                              │ 자기 @Transactional
                                              │ Payment row 영속 (PAID 또는 FAILED)
                                              │
                                ┌─────────────┴─────────────┐
                            PAID                          FAILED
                                │                              │
                                ▼                              ▼
                  T2 trade.confirm()              T1' trade.cancel()
                     자기 @Transactional             자기 @Transactional (보상)
                     RESERVED → CONFIRMED            RESERVED → CANCELED
                     Product TRADING → SOLD          Product TRADING → AVAILABLE
                                │                              │
                                ▼                              ▼
                  TradeConfirmResponse           throw PAYMENT_FAILED (402)
                  { trade, payment }             (Payment row 는 FAILED 로 남아있음)
```

핵심 포인트:
- **각 단계가 자기 트랜잭션** — 단계 끝나면 commit, 다음 단계는 새 트랜잭션
- **보상은 새 트랜잭션** — 옛 트랜잭션을 롤백하는 게 아니라 반대 효과를 일으키는 새 commit
- **Payment row 는 FAILED 든 PAID 든 영속** — 보상 후에도 결제 시도 흔적 남음 (감사 / 재시도 / 환불 근거)

---

## 구현 노트

### 왜 Saga 메서드 자체에 `@Transactional` 안 두나

```java
// ❌ 잘못된 패턴
@Transactional
public TradeConfirmResponse confirm(Long buyerId, Long tradeId) {
    Trade trade = loadAndValidate(...);
    PaymentResponse payment = paymentService.charge(trade);  // 외부 PG 호출
    if (payment.status() == PAID) {
        return tradeService.confirm(...);
    } else {
        tradeService.cancel(...);
        throw new BusinessException(PAYMENT_FAILED);
    }
}
```

위처럼 묶으면:
1. PG 호출 동안 DB connection 점유 → 동시 거래량 증가 시 connection pool 고갈
2. PG 가 돈 받았는데 우리 DB 트랜잭션이 어디서든 실패하면 전체 롤백 → Payment 영속 사라짐 → 이중장부

**올바른 패턴 (현재 구현)**: Saga 메서드 자체엔 `@Transactional` 없음. 각 단계 (paymentService.charge, tradeService.confirm, tradeService.cancel) 가 자기 `@Transactional`. Saga 는 결과 읽고 분기만 함.

### 왜 결제 FAILED 도 영속화하나

`PaymentService.charge` 안에서 PG 가 FAILED 응답 줘도 BusinessException 을 throw 하지 않고 `Payment.markFailed()` 로 영속화 후 정상 반환. 이유:

- BusinessException 던지면 자기 `@Transactional` 롤백 → FAILED row 사라짐 → 사용자가 "결제 시도했었나?" 추적 불가
- Saga 가 `payment.status()` 확인해 분기하려면 응답이 정상 반환되어야 함

### 왜 멱등성 키 (gatewayTxId) 가 필요한가

PG 응답이 네트워크 손실로 우리에게 도달 안 하는 경우 (실제 운영에서 자주 발생):
- 우리: "결제 안 됐다고 생각하고 다시 시도"
- PG: "이미 결제 처리했어요"
- 결과: 같은 거래에 결제 2번 → 이중 청구

**`gatewayTxId`** 로 멱등성 보장:
- `PaymentService.charge` 첫 줄에 `paymentRepository.existsByTradeId(trade.id)` 가드 → 같은 trade 에 Payment row 있으면 `PAYMENT_ALREADY_PROCESSED` 던짐
- PG 도 `gatewayTxId` (또는 idempotency key) 로 같은 거래의 중복 청구 차단 (실제 PG 들이 이 키 지원)

---

## 의도된 한계 — 부분 Saga

본 구현은 가장 흔한 실패 (PG 결제 실패) 만 자동 보상. 나머지 0.x% 빈도 케이스는 운영자 수동.

| 시나리오 | 발생 빈도 | 현재 처리 | Outbox 합류 시 |
|---|---|---|---|
| ✅ T1 결제 실패 (PG FAILED) | 흔함 (~1-3% 운영) | 자동 보상 — `trade.cancel` + Product 복원 | — |
| ❌ T2 거래 확정 실패 (PG PAID 후 DB 오류) | 매우 드묾 | log warning + 운영자 수동 환불 | Outbox 에 `payment.refund` 이벤트 → 스케줄러 자동 환불 |
| ❌ T1 commit 후 서버 크래시 (T2 진입 전) | 드묾 (배포 / OOM / SIGKILL) | orphan (PAID + RESERVED) | Outbox 에 "T2 pending" → 재시작 후 스케줄러 이어받기 |
| ❌ T1' 보상 자체 실패 (trade.cancel DB 오류) | 드묾 | log error + 운영자 수동 | Outbox 에 보상 이벤트 → 지수 백오프 재시도 |

### 왜 지금 Outbox 안 했나
- 본 PR (Saga 1차) 의 목표는 **Saga 패턴 자체 가치 입증** — 각 단계 독립 트랜잭션 + 보상 + 멱등성 키. T1 실패 자동 보상까지가 그 시연 범위
- Outbox 합류 = `outbox_event` 테이블 + 스케줄러 + 멱등성 키 처리 + 통합 테스트 추가 → **1.5~2일 작업**
- 3주 단독 일정에서 ADR 4종 + 멀티 인스턴스 채팅 + AWS 실배포 우선 → Outbox 는 **ADR-7 후보 / 다음 단계**

### "부분 Saga 가 충분히 좋은" 근거
- 운영 환경에서 T2 / 크래시 / 보상 자체 실패는 합쳐도 0.x% 빈도
- PG 환불은 영업일 1~3일 소요 — 자동 vs 수동 차이가 그렇게 크지 않음
- 0.x% 빈도면 운영자 알림 (Slack / PagerDuty) 으로 가는 게 ROI 좋음
- **자동화 vs 인프라 복잡도의 의도된 균형**

---

## 검증 — 회귀 가드

| 테스트 | 종류 | 책임 |
|---|---|---|
| `TradeSagaServiceTest` | 단위 (Mockito) | PG PAID/FAILED 분기 / 보상 호출 검증 |
| `TradeSagaCompensationIT` | 통합 (`@SpringBootTest` + `ControllablePaymentGateway`) | **핵심**: PG 강제 FAILED → Product 상태 원복 + Trade 상태 CANCELED + Payment 영속 1건 (FAILED) |

`ControllablePaymentGateway` 는 테스트 전용 빈 (`@Profile("test")`) 으로 PG 응답을 외부에서 조작. 일반 `MockPaymentGateway` (`@Profile("!prod")`) 는 항상 성공 — 평소엔 happy path 시연만 함.

---

## Consequences

### 얻은 것
- **거래 확정 + 결제 분리 안전성** — PG 실패해도 데이터 무결성 유지 (`TradeSagaCompensationIT` 입증)
- **자기 트랜잭션 분리 → connection pool 효율** — PG latency 가 DB connection 을 점유 안 함
- **멱등성 키 → 중복 청구 차단**
- **모놀리스 안에서 분산 트랜잭션 패턴 학습** — 면접에서 "Saga 패턴 설명" 답변 가능

### 대가
- T2 / 크래시 / 보상 실패 자동화 미구현 → 운영자 의존 (Slack alert + manual refund)
- 한 단계가 자기 트랜잭션이라 단계 간 일관성은 "**eventual consistency**" — 짧은 순간 (수십 ms) 동안 결제는 됐는데 거래는 안 된 상태 가능 (사용자에겐 안 보임, 마이크로초 단위)

### 언제 재검토하나
- PG 가 실제 외부 연동되어 실패율 / 응답 시간 / 네트워크 손실 시나리오 측정되면 → Outbox 합류 우선순위 ↑
- 거래량이 분당 100+ 로 커져 운영자 수동 환불이 burden 이 되면 → Outbox 필수

---

## 면접 대비

### Q. "Saga 패턴이 뭔가요?"

**A**: "분산 트랜잭션 (또는 cross-domain) 흐름을 보장 못 하는 환경에서, 여러 단계를 **각각 독립 트랜잭션** 으로 쪼개고, 중간 실패 시 이미 commit 된 단계를 **보상 트랜잭션** 으로 되돌리는 패턴입니다. 두 가지 구현 방식 — Choreography (이벤트 cascade) 와 Orchestration (중앙 조율자). 저는 Orchestration 으로 갔습니다 — 모놀리스에서 흐름 추적 / 디버깅이 유리해서."

### Q. "보상 못 막는 케이스는?"

**A**: "세 가지. 운영 환경에서 합쳐도 0.x% 빈도라 의도적으로 1차에서 제외:
1. **T2 실패** (PG PAID 후 trade.confirm 단계 DB 오류) — 현재 log warning + 운영자 수동 환불
2. **T1 commit 후 서버 크래시** — orphan 상태 (PAID + RESERVED)
3. **T1' 보상 자체 실패** — 현재 log error + 운영자 수동

전부 **Outbox 패턴** 으로 해결 가능 — `outbox_event` 테이블에 단계별 pending 표시 + 스케줄러가 멱등성 키로 자동 재시도. 본 구현 일정 (3주) 안에선 ADR 4종 + 멀티 인스턴스 채팅 + AWS 실배포 우선이라 ADR-7 후보로 분리."

### Q. "왜 Choreography 안 했나요?"

**A**: "모놀리스 안에서는 Choreography 의 장점 (낮은 결합) 이 작습니다. 이벤트 broker (Kafka 등) 추가 인프라가 필요하고, 흐름 추적이 분산 트레이싱 의존이 됩니다. Orchestration 은 한 클래스 안에 흐름이 보이는 게 디버깅 / 가르치기 / 면접 시연 모두 강점. **추후 MSA 분리되면** Choreography 로 마이그레이션 검토."

---

## References

- Chris Richardson "Microservices Patterns" 4장 (Saga)
- 본 프로젝트 구현: [TradeSagaService.java](../../src/main/java/com/portfolio/used_trade/trade/service/TradeSagaService.java)
- 회귀 가드: [TradeSagaCompensationIT](../../src/test/java/com/portfolio/used_trade/trade/service/TradeSagaCompensationIT.java)
