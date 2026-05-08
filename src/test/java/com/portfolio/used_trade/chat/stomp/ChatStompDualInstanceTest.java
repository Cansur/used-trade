package com.portfolio.used_trade.chat.stomp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.used_trade.UsedTradeApplication;
import com.portfolio.used_trade.chat.domain.ChatRoom;
import com.portfolio.used_trade.chat.dto.MessageResponse;
import com.portfolio.used_trade.chat.dto.MessageSendRequest;
import com.portfolio.used_trade.chat.repository.ChatRoomRepository;
import com.portfolio.used_trade.chat.repository.MessageRepository;
import com.portfolio.used_trade.product.domain.Category;
import com.portfolio.used_trade.product.domain.Product;
import com.portfolio.used_trade.product.repository.CategoryRepository;
import com.portfolio.used_trade.product.repository.ProductRepository;
import com.portfolio.used_trade.user.domain.Role;
import com.portfolio.used_trade.user.domain.User;
import com.portfolio.used_trade.user.repository.UserRepository;
import com.portfolio.used_trade.user.service.JwtTokenProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * STOMP 다중 인스턴스 통합 테스트 — <b>ADR-3 의 핵심 시연</b>.
 *
 * <p>같은 채팅방의 두 사용자가 <b>다른 인스턴스</b> 에 STOMP 로 붙은 상황에서, 한쪽이
 * SEND 한 메시지가 같은 Redis Pub/Sub 채널을 통해 다른 인스턴스로 릴레이되어 양쪽 모두
 * 수신함을 입증한다.
 *
 * <p><b>왜 두 인스턴스가 필요한가?</b><br>
 * SimpleBroker 는 in-process 한정 — 단일 인스턴스 안에서만 broadcast 한다. 다른 JVM
 * (다른 인스턴스) 에 붙은 SUBSCRIBE 는 그 broadcast 를 못 본다. ADR-3 가 막으려는 결함이
 * 정확히 이 부분.
 *
 * <p><b>구성</b>
 * <ul>
 *   <li>인스턴스 A : {@code @SpringBootTest(RANDOM_PORT)} 가 자동 시동</li>
 *   <li>인스턴스 B : {@link SpringApplicationBuilder} 로 BeforeEach 에서 추가 시동</li>
 *   <li>같은 MySQL / 같은 Redis 공유 (로컬 docker-compose)</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@DisplayName("chat STOMP 다중 인스턴스 통합 테스트 (ADR-3)")
class ChatStompDualInstanceTest {

    @LocalServerPort private int portA;

    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private UserRepository userRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ChatRoomRepository chatRoomRepository;
    @Autowired private MessageRepository messageRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ObjectMapper objectMapper;

    private ConfigurableApplicationContext contextB;
    private int portB;

    private User seller;
    private User buyer;
    private Product product;
    private ChatRoom room;
    private String sellerToken;
    private String buyerToken;

    @BeforeEach
    void setUp() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        String hash = passwordEncoder.encode("trade1234");

        seller = userRepository.save(
                User.create("seller-dual-" + unique + "@used-trade.com", hash, "sellerD"));
        buyer = userRepository.save(
                User.create("buyer-dual-" + unique + "@used-trade.com", hash, "buyerD"));

        Category category = categoryRepository.findAll().get(0);
        product = productRepository.save(Product.create(
                seller, category, "dual-target-" + unique, "ADR-3 시연용", 100_000L));
        room = chatRoomRepository.save(ChatRoom.create(product, buyer));

        sellerToken = jwtTokenProvider.createAccessToken(seller.getId(), seller.getEmail(), Role.USER);
        buyerToken = jwtTokenProvider.createAccessToken(buyer.getId(), buyer.getEmail(), Role.USER);

        // 인스턴스 B 시동 — 같은 main 클래스, 임의 포트, 같은 docker MySQL/Redis 공유.
        // application.yaml 의 server.port=8080 을 override 하기 위해 command-line args 형태로 전달.
        contextB = new SpringApplicationBuilder(UsedTradeApplication.class)
                .run(
                        "--server.port=0",
                        // devtools 의 자동 restart 가 테스트 컨텍스트를 흔들지 않도록 끔
                        "--spring.devtools.restart.enabled=false",
                        "--spring.devtools.livereload.enabled=false"
                );
        portB = ((WebServerApplicationContext) contextB).getWebServer().getPort();
    }

    @AfterEach
    void tearDown() {
        if (contextB != null) {
            contextB.close();
        }
        messageRepository.findAll().stream()
                .filter(m -> m.getChatRoom().getId().equals(room.getId()))
                .forEach(messageRepository::delete);
        chatRoomRepository.deleteById(room.getId());
        productRepository.deleteById(product.getId());
        userRepository.deleteById(buyer.getId());
        userRepository.deleteById(seller.getId());
    }

    @Test
    @DisplayName("buyer 가 인스턴스 A, seller 가 인스턴스 B 에 붙어도 메시지가 양쪽에 도달한다 (Redis Pub/Sub 릴레이)")
    void messageRelaysAcrossInstancesViaRedisPubSub() throws Exception {
        BlockingQueue<MessageResponse> buyerInbox = new LinkedBlockingQueue<>();
        BlockingQueue<MessageResponse> sellerInbox = new LinkedBlockingQueue<>();

        // buyer → 인스턴스 A
        StompSession buyerSession = connectAndSubscribe(portA, buyerToken, room.getId(), buyerInbox);
        // seller → 인스턴스 B (다른 인스턴스)
        StompSession sellerSession = connectAndSubscribe(portB, sellerToken, room.getId(), sellerInbox);

        // buyer 가 인스턴스 A 로 SEND — Redis Pub/Sub 으로 인스턴스 B 까지 릴레이되어야 함
        buyerSession.send(
                "/app/chat/rooms/" + room.getId() + "/messages",
                new MessageSendRequest("다른 인스턴스에서도 받히나요?"));

        MessageResponse atBuyer = buyerInbox.poll(5, TimeUnit.SECONDS);
        MessageResponse atSeller = sellerInbox.poll(5, TimeUnit.SECONDS);

        assertThat(atBuyer)
                .as("buyer (인스턴스 A) 인박스 — publisher 자기 인스턴스에도 Redis 통과 후 broadcast")
                .isNotNull();
        assertThat(atSeller)
                .as("seller (인스턴스 B) 인박스 — Redis Pub/Sub 으로 cross-instance 릴레이 됐어야 함")
                .isNotNull();

        assertThat(atSeller.id()).isEqualTo(atBuyer.id());
        assertThat(atSeller.content()).isEqualTo("다른 인스턴스에서도 받히나요?");
        assertThat(atSeller.senderId()).isEqualTo(buyer.getId());
        assertThat(atSeller.roomId()).isEqualTo(room.getId());

        // DB 영속 1건 (한 번만 저장된 동일 메시지)
        long persisted = messageRepository.findAll().stream()
                .filter(m -> m.getChatRoom().getId().equals(room.getId()))
                .count();
        assertThat(persisted).isEqualTo(1L);

        buyerSession.disconnect();
        sellerSession.disconnect();
    }

    // ---------- 헬퍼 ----------

    private StompSession connectAndSubscribe(int port, String token, Long roomId,
                                             BlockingQueue<MessageResponse> inbox) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
        converter.setObjectMapper(objectMapper);
        client.setMessageConverter(converter);

        WebSocketHttpHeaders handshake = new WebSocketHttpHeaders();
        StompHeaders connect = new StompHeaders();
        connect.add("Authorization", "Bearer " + token);

        StompSession session = client.connectAsync(
                "ws://localhost:" + port + "/ws",
                handshake,
                connect,
                new StompSessionHandlerAdapter() {}
        ).get(5, TimeUnit.SECONDS);

        session.subscribe("/topic/chat/rooms/" + roomId, new StompFrameHandler() {
            @Override public Type getPayloadType(StompHeaders headers) {
                return MessageResponse.class;
            }

            @Override public void handleFrame(StompHeaders headers, Object payload) {
                inbox.add((MessageResponse) payload);
            }
        });

        // SUBSCRIBE 가 broker 에 등록 + Redis 채널 구독이 startup 되도록 짧게 대기
        Thread.sleep(300);
        return session;
    }
}
