# ADR-3 - Redis Pub/Sub

### 상태

- **Status**: Accepted (2026-05-07)

## 배경

AWS 환경에서 ALB 뒤에 2개의 EC2 인스턴스로 배포합니다.<br>
라운드 로빈 분산으로 각 사용자가 어느 인스턴스에 연결될지는 결정되지 않습니다.

채팅 기능은 `WebSocket/STOMP`로 구현했지만, Spring의 기본 메시지 브로커(`SimpleBroker`)는 `in-process`로 동작합니다. <br>
그렇기에 한 JVM안에서만 브로드캐스트를 진행하고 다른 JVM의 구독자에게는 전달이 안됩니다.

같은 채팅방의 buyer와 seller가 다른 EC2 인스턴스에 붙으면 한쪽이 보낸 메시지가
다른 쪽에 도달하지 않습니다.

- 다른 인스턴스에 연결된 구독자에게 메시지 전달
- 추가 인프라 부담 최소화
- 단일/다중 인스턴스 환경에서 코드 경로 동일

<br>

## 결론

채팅 메시지의 cross-instance 브로드캐스트를 `Redis Pub/Sub`으로 구현

- 모든 인스턴스가 단일 채널 `chat.broadcast` 구독
- Publisher 발행 → 자기 자신 포함 모든 인스턴스의 Subscriber 수신 
  → 각 인스턴스의 STOMP 구독자에게 브로드캐스트

<br>

## 비교

|  | Redis Pub/Sub | Sticky Session | Kafka |
|---|---|---|---|
| 인프라 추가 | 없음 (이미 사용 중) | 없음 (LB 설정만) | Kafka 클러스터 추가 |
| 메시지 일관성 | 모든 인스턴스가 받음 | 한 방의 두 사용자 문제 그대로 | 토픽 구독으로 일관 |
| 운영 복잡도 | 낮음 | 낮음 | 높음 (파티션·오프셋·리밸런싱) |


**Sticky Session 선택 X**
- 같은 사용자는 같은 인스턴스로 라우팅되지만, 한 채팅방의 buyer/seller가 다른 인스턴스에 붙는 핵심 문제는 그대로

**Kafka 선택 X**
- 채팅 메시지는 이미 MySQL에 영속화돼 있고, 프로젝트 트래픽이 Pub/Sub 한계를 초과하지 않음
- 운영 복잡도(파티션·오프셋·리밸런싱) 대비 이득 미미

<br>


## 구현 방법

### 1. 발행-구독 흐름

ChatService(Publisher) → Redis `chat.broadcast` 채널 → 모든 인스턴스의 Subscriber 
→ 각 인스턴스의 SimpMessagingTemplate으로 STOMP broadcast

- 자기 자신에게도 도달하므로 **단일/다중 인스턴스 코드 경로가 동일**.

### 2. Publisher — ChatService
```java
@Service
public class ChatService {
    @Transactional
    public MessageSendResponse sendMessage(...) {
        Message saved = messageRepository.save(message);  // MySQL 영속
        publisher.publish(ChatBroadcastEvent.from(saved));  // Redis 발행
        return MessageSendResponse.from(saved);
    }
}
```
- 영속화는 **publisher 한 곳에서만**. Subscriber는 broadcast만 함

### 3. Subscriber — RedisChatSubscriber
```java
@Component
public class RedisChatSubscriber implements MessageListener {
    public void onMessage(Message redisMessage, byte[] pattern) {
        ChatBroadcastEvent event = deserialize(redisMessage);
        simpMessagingTemplate.convertAndSend(
            "/topic/chat/rooms/" + event.roomId(),
            event.payload()
        );
    }
}
```
- Redis에서 메시지를 받아 자기 JVM의 STOMP 구독자에게 broadcast

### 측정 결과

| 시나리오 | 결과 |
|---|---|
| buyer→A·seller→B SUBSCRIBE | A에 보낸 메시지가 buyer·seller 모두 5초 이내 수신 |
| DB 영속 | 1건 (publisher가 한 번만 저장) |

<br>

## 에러 상황

### 1. STOMP endpoint 추가 등록
상황 : 처음엔 `/ws` 단일 endpoint만 등록

발견 :
- 통합 테스트(`ChatStompDualInstanceTest`)는 WebSocketStompClient + StandardWebSocketClient로 raw `/ws` 사용
- 브라우저 데모(`chat-demo.html`)에선 SockJS fallback 필요 - raw `/ws`는 SockJS handshake를 통과 못 함

결론 : WebSocketConfig에 두 endpoint 둘 다 등록
- `/ws` - raw WebSocket (통합 테스트용)
- `/ws-sockjs` - SockJS (브라우저 호환용)

### 2. commit 전 발행의 정합성 한계
상황 : ChatService.sendMessage 의 흐름
```
1. messageRepository.save(message)        // 영속성 컨텍스트
2. publisher.publish(event)               // Redis 발행
3. (메서드 종료 → @Transactional commit)
```
문제 : Redis 발행이 DB commit 전에 일어남. commit 실패 시 Redis는 받았는데 DB엔 없는 불일치 상태 가능

해결 방안 : `@TransactionalEventListener(AFTER_COMMIT)`로 발행을 commit 후로 옮김

<br>

## 참조

- [ChatService](../../src/main/java/com/portfolio/used_trade/chat/service/ChatService.java) — Publisher
- [RedisChatSubscriber](../../src/main/java/com/portfolio/used_trade/chat/pubsub/RedisChatSubscriber.java) — Subscriber + STOMP broadcast
- [RedisChatPubSubConfig](../../src/main/java/com/portfolio/used_trade/chat/pubsub/RedisChatPubSubConfig.java) — `chat.broadcast` 채널 구독 설정
- [WebSocketConfig](../../src/main/java/com/portfolio/used_trade/chat/config/WebSocketConfig.java) — `/ws` + `/ws-sockjs` dual endpoint (에러 상황 1)