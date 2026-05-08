package com.portfolio.used_trade.chat.stomp;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
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
 * STOMP 단일 인스턴스 통합 테스트 — chat-1 의 회귀 보장.
 *
 * <p>실제 Spring Boot 컨텍스트 + RANDOM_PORT 위에 raw WebSocket 으로 두 클라이언트를 붙여
 * 다음을 검증한다:
 * <ul>
 *   <li>STOMP CONNECT 단계의 JWT 검증 (Authorization 헤더)</li>
 *   <li>SUBSCRIBE 단계의 채팅방 참여자 가드 (JwtChannelInterceptor)</li>
 *   <li>SEND 시 SimpMessagingTemplate broadcast → 같은 인스턴스에 붙은 다른 SUBSCRIBE 도 수신</li>
 *   <li>본문이 messages 테이블에 영속</li>
 * </ul>
 *
 * <p><b>왜 단일 인스턴스인가?</b><br>
 * 같은 인스턴스 안에서는 SimpleBroker 가 in-process 로 broadcast 한다 — Pub/Sub 없이도 동작.
 * 다중 인스턴스 환경의 한계 / Redis Pub/Sub 으로 해결하는 시연은 별도 테스트.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@DisplayName("chat STOMP 단일 인스턴스 통합 테스트")
class ChatStompSingleInstanceTest {

    @LocalServerPort private int port;

    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private UserRepository userRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ChatRoomRepository chatRoomRepository;
    @Autowired private MessageRepository messageRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ObjectMapper objectMapper;

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
                User.create("seller-stomp-" + unique + "@used-trade.com", hash, "sellerS"));
        buyer = userRepository.save(
                User.create("buyer-stomp-" + unique + "@used-trade.com", hash, "buyerB"));

        Category category = categoryRepository.findAll().get(0);
        product = productRepository.save(Product.create(
                seller, category, "stomp-target-" + unique, "stomp single", 100_000L));
        room = chatRoomRepository.save(ChatRoom.create(product, buyer));

        sellerToken = jwtTokenProvider.createAccessToken(seller.getId(), seller.getEmail(), Role.USER);
        buyerToken = jwtTokenProvider.createAccessToken(buyer.getId(), buyer.getEmail(), Role.USER);
    }

    @AfterEach
    void tearDown() {
        messageRepository.findAll().stream()
                .filter(m -> m.getChatRoom().getId().equals(room.getId()))
                .forEach(messageRepository::delete);
        chatRoomRepository.deleteById(room.getId());
        productRepository.deleteById(product.getId());
        userRepository.deleteById(buyer.getId());
        userRepository.deleteById(seller.getId());
    }

    @Test
    @DisplayName("두 참여자가 같은 인스턴스에 붙어 SEND/SUBSCRIBE 하면 서로 메시지를 받는다")
    void participantsExchangeMessages_singleInstance() throws Exception {
        BlockingQueue<MessageResponse> sellerInbox = new LinkedBlockingQueue<>();
        BlockingQueue<MessageResponse> buyerInbox = new LinkedBlockingQueue<>();

        StompSession sellerSession = connectAndSubscribe(sellerToken, room.getId(), sellerInbox);
        StompSession buyerSession = connectAndSubscribe(buyerToken, room.getId(), buyerInbox);

        // buyer 가 SEND — seller 와 buyer 모두 (자기 자신 포함) /topic 으로 broadcast 받는다
        buyerSession.send(
                "/app/chat/rooms/" + room.getId() + "/messages",
                new MessageSendRequest("안녕하세요, 거래 가능한가요?"));

        MessageResponse atSeller = sellerInbox.poll(5, TimeUnit.SECONDS);
        MessageResponse atBuyer = buyerInbox.poll(5, TimeUnit.SECONDS);

        assertThat(atSeller).as("seller 인박스에 메시지가 도착").isNotNull();
        assertThat(atSeller.content()).isEqualTo("안녕하세요, 거래 가능한가요?");
        assertThat(atSeller.senderId()).isEqualTo(buyer.getId());
        assertThat(atSeller.roomId()).isEqualTo(room.getId());

        assertThat(atBuyer).as("buyer 인박스에도 자기 메시지가 도착 (echo)").isNotNull();
        assertThat(atBuyer.id()).isEqualTo(atSeller.id());

        // DB 영속화 확인
        long persisted = messageRepository.findAll().stream()
                .filter(m -> m.getChatRoom().getId().equals(room.getId()))
                .count();
        assertThat(persisted).isEqualTo(1L);

        sellerSession.disconnect();
        buyerSession.disconnect();
    }

    // ---------- 헬퍼 ----------

    private StompSession connectAndSubscribe(String token, Long roomId,
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

        // SUBSCRIBE 가 broker 에 등록될 시간 짧게 부여 — 안 그러면 SEND 가 빨라 메시지 유실 가능
        Thread.sleep(200);
        return session;
    }
}
