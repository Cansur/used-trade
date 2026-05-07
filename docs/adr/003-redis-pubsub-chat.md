# ADR-3 — 다중 서버 채팅 메시지 일관성: Redis Pub/Sub

- **Status**: Accepted (2026-05-07)
- **Context**: Phase 2 / W2 — chat-2 (다중 인스턴스 환경의 STOMP broadcast 한계 해결)
- **Related**: ADR-1 (모듈러 모놀리스), ADR-2 (낙관적 락)

---

## 결정

채팅 메시지 broadcast 를 **Redis Pub/Sub** 으로 인스턴스 간 릴레이.

- 모든 인스턴스가 단일 채널 `chat.broadcast` 를 구독
- `ChatMessageController.sendMessage` 는 영속화 후 `RedisChatPublisher.publish(roomId, response)` 호출 — STOMP broadcast 를 직접 안 함
- 모든 인스턴스의 `RedisChatSubscriber` 가 publish 메시지를 수신해 자기 인스턴스의 `SimpMessagingTemplate.convertAndSend("/topic/chat/rooms/{id}", payload)` 로 broadcast
- 자기 자신에게도 도달 — 단일 인스턴스 / 다중 인스턴스 코드 경로가 동일

---

## Context — 무엇이 막히는가

Spring 의 `SimpleBroker` 는 **in-process** 로 동작한다. 한 인스턴스 안에서 SUBSCRIBE 한 모든 세션에 broadcast 하지만, 다른 JVM (다른 인스턴스) 에 붙은 SUBSCRIBE 는 그 broadcast 를 못 본다.

ALB / 라운드 로빈 환경에서 같은 채팅방의 두 사용자가 다른 인스턴스에 붙으면 (예: buyer 가 인스턴스 A, seller 가 인스턴스 B) 한쪽이 SEND 한 메시지가 다른 쪽에는 도달하지 않는다 — 운영 관점에서 메시지 유실로 보이는 결함.

---

## 옵션 비교

| 축 | Redis Pub/Sub (선택) | Sticky Session (ALB/Nginx) | Kafka | Hazelcast / Embedded JGroups |
|---|---|---|---|---|
| 인프라 추가 | Redis 만 — 이 프로젝트는 이미 사용 (세션/캐시) | LB 설정만 | Kafka 클러스터 추가 | 라이브러리 추가 |
| 메시지 일관성 | 모든 인스턴스가 받음 | 같은 사용자는 같은 인스턴스로 라우팅 — 한 방의 두 사용자가 다른 인스턴스에 붙는 문제 그대로 | 토픽 구독으로 일관 | 클러스터 멤버에 broadcast |
| 영속성 | 없음 (이미 MySQL 영속) | — | 있음 (replay 가능) | 옵션 |
| 장애 시 결과 | Redis 다운 → 릴레이 중단 (메시지는 MySQL 에만 남음) | LB 다운 → 라우팅 자체 마비 | Broker 다운 → 동일 | 멤버 분리 시 split-brain |
| 운영 복잡도 | 낮음 | 낮음 | 높음 (파티션/오프셋/리밸런싱) | 중 |
| 부하 한계 | 단일 채널은 약 ~수만 msg/s (벤치 의존) | 부하 분산 효과 ↓ (특정 인스턴스 집중) | 매우 높음 | 중 |

**기각 이유:**
- **Sticky Session**: 핵심 문제를 해결하지 못한다. 같은 사용자는 같은 인스턴스로 가지만, **한 방의 두 사용자가 다른 인스턴스에 붙으면 동일한 결함이 그대로** 남는다. 또한 부하 분산 효율도 떨어진다.
- **Kafka**: 영속성 / replay / 처리량이 강점이지만 본 프로젝트의 채팅 시나리오는 영속성을 이미 MySQL 에서 갖고, 처리량 요구가 Pub/Sub 이상으로 커지지 않는다. 운영 복잡도 대비 이득 미미.
- **Hazelcast / JGroups**: 임베디드 클러스터링은 멤버 디스커버리 / 네트워크 분리 / split-brain 등 분산 시스템 고민이 코드 안으로 들어온다. Redis 를 outsourcing 하는 것보다 학습/운영 비용이 큼.

---

## Before / After 통합 검증

| | Before — SimpleBroker only (ADR-3 미적용) | After — Redis Pub/Sub 적용 |
|---|---|---|
| 같은 인스턴스 내 broadcast | OK (in-process) | OK (Redis 통과 후 동일) |
| **다른 인스턴스 간 broadcast** | **메시지 유실 — 다른 인스턴스 SUBSCRIBE 는 못 받음** | **양쪽 모두 수신 (실측)** |
| DB 영속화 | 1건 | 1건 (한 번만 저장) |
| 코드 경로 단일성 | 단일 vs 다중 분기 | 동일 — publisher 경로 |

**실측 (`ChatStompDualInstanceTest`):**
- 인스턴스 A (RANDOM_PORT) + 인스턴스 B (`SpringApplicationBuilder` 로 별도 시동, 같은 Redis 공유)
- buyer → A 에 SUBSCRIBE, seller → B 에 SUBSCRIBE
- buyer 가 A 로 SEND → A 의 buyer 와 B 의 seller 모두 5초 이내 수신
- DB 영속 1건 (publisher 가 한 번만 저장)
- assertion 4종 (buyer 수신, seller 수신, id/content/sender 일치, DB 영속 1건) 모두 통과

---

## 구현 노트

### 채널 설계 — 단일 vs 방별

1차 PR 은 단일 채널 `chat.broadcast` + payload 에 `roomId` 포함. 방별 채널 분리 (`chat.broadcast.{roomId}`) 는 채널 fan-out 비용이 의미있어질 때 분리. 단일 채널이 더 단순하고, subscriber 측이 어차피 자기 인스턴스의 토픽으로 분기 broadcast 한다.

### 자기 인스턴스 echo

`RedisChatPublisher.publish` 가 보낸 메시지는 **자기 인스턴스의 subscriber 에게도 도달**한다. 즉 publisher 가 직접 `SimpMessagingTemplate.convertAndSend` 를 호출하지 않아도 자기 인스턴스 SUBSCRIBE 가 메시지를 받는다. 단일/다중 인스턴스 코드 경로가 동일해지는 것이 핵심.

만약 echo 를 피하려면 publish 페이로드에 `originInstanceId` 를 박고 subscriber 가 자기 인스턴스 publish 를 무시하면 되지만, 추가 복잡도 대비 이득이 없어 1차에는 그대로 둔다.

### 영속화는 한 번만

`ChatService.sendMessage` 가 메시지를 MySQL 에 저장한 후 publisher 호출. subscriber 는 영속화하지 않고 broadcast 만 — Redis 메시지가 손실되어도 DB 에 한 번 저장된 사실은 유지. ChatStompDualInstanceTest 의 "DB 1건" assertion 이 이 회귀를 박는다.

### endpoint dual 등록

`WebSocketConfig` 가 `/ws` (raw) + `/ws-sockjs` (SockJS) 두 endpoint 를 등록. 통합 테스트는 `WebSocketStompClient + StandardWebSocketClient` 로 raw `/ws` 사용 — SockJS handshake 의 부속 핸들러를 통과하지 않아 클라이언트 코드가 단순. 브라우저는 SockJS fallback 으로 호환.

### `@Recover` 회귀와의 관계

ADR-2 에서 잡은 "Spring Retry 가 `BusinessException` 을 wrap" 회귀와 무관. chat 의 retry 는 없음 — 메시지 발화 충돌 시나리오가 없기 때문. 향후 outbox/retry 가 합류하면 ADR-2 의 패턴 (`@Recover(BusinessException) → throw`) 그대로 적용.

---

## Consequences

**얻은 것:**
- 다중 인스턴스 환경의 채팅 메시지 일관성
- 단일/다중 코드 경로 동일 — 단일 인스턴스 환경에서도 publisher 경로 사용
- Redis 만으로 해결 — 추가 인프라 0
- 통합 테스트로 회귀 보장 (`ChatStompDualInstanceTest`)

**대가:**
- Redis 가 단일 장애점 (single point of failure). Redis 다운 → 메시지 릴레이 중단. **다만 메시지는 MySQL 에 영속화되므로** 사용자가 새로 SUBSCRIBE 하거나 페이지 새로고침 시 messages REST API 로 누락 메시지 복구 가능.
- 메시지 latency 증가 (Redis 왕복 ~수 ms). 본 프로젝트 시나리오에서는 무시 가능.
- 자기 인스턴스 echo 도 Redis 를 통과 — 단일 인스턴스에서도 약간의 오버헤드. 운영 환경에서 이게 문제가 되면 `originInstanceId` 필터 도입 검토.

**언제 재검토하나:**
- 채팅 트래픽이 단일 채널 한계를 초과 (~수만 msg/s) 하면 채널을 방별/샤드별로 분리.
- 메시지 영속성 / replay 가 핵심 요구가 되면 Kafka 로 이전.
- Redis 장애 시 즉시 전환 가능한 fallback 이 필요해지면 sentinel/cluster + 다중 broker 구성.

---

## 검증 — 본 ADR 의 회귀 가드

| 테스트 | 종류 | 책임 |
|---|---|---|
| `ChatStompSingleInstanceTest` | 통합 (단일 인스턴스) | STOMP 핸드셰이크 / JWT CONNECT / SUBSCRIBE 가드 / publisher 경로 정상 동작 |
| `ChatStompDualInstanceTest` | 통합 (다중 인스턴스) | **Redis Pub/Sub 으로 cross-instance 릴레이 됨을 입증** — ADR-3 핵심 시연 |

총 120 PASS / 0 FAIL.

---

## References

- 본문 측정값은 commit 시점 로컬 환경 (Docker MySQL 8.0, Redis 7) 기준
- Spring Data Redis Pub/Sub: `RedisMessageListenerContainer`, `MessageListener`
- Spring Messaging STOMP: `SimpMessagingTemplate`, `WebSocketStompClient`
