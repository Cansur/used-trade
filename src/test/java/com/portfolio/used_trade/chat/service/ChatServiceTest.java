package com.portfolio.used_trade.chat.service;

import com.portfolio.used_trade.chat.domain.ChatRoom;
import com.portfolio.used_trade.chat.domain.Message;
import com.portfolio.used_trade.chat.domain.MessageType;
import com.portfolio.used_trade.chat.dto.ChatRoomResponse;
import com.portfolio.used_trade.chat.dto.MessageCursorPageResponse;
import com.portfolio.used_trade.chat.dto.MessageResponse;
import com.portfolio.used_trade.chat.repository.ChatRoomRepository;
import com.portfolio.used_trade.chat.repository.MessageRepository;
import com.portfolio.used_trade.common.exception.BusinessException;
import com.portfolio.used_trade.common.exception.ErrorCode;
import com.portfolio.used_trade.product.domain.Category;
import com.portfolio.used_trade.product.domain.Product;
import com.portfolio.used_trade.product.repository.ProductRepository;
import com.portfolio.used_trade.user.domain.User;
import com.portfolio.used_trade.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChatService 단위 테스트")
class ChatServiceTest {

    @Mock private ChatRoomRepository chatRoomRepository;
    @Mock private MessageRepository messageRepository;
    @Mock private UserRepository userRepository;
    @Mock private ProductRepository productRepository;

    @InjectMocks private ChatService chatService;

    private User seller;
    private User buyer;
    private User stranger;
    private Category category;
    private Product product;

    @BeforeEach
    void setUp() {
        seller = User.create("seller@used-trade.com", "$2a$10$DUMMY", "판매자");
        ReflectionTestUtils.setField(seller, "id", 100L);

        buyer = User.create("buyer@used-trade.com", "$2a$10$DUMMY", "구매자");
        ReflectionTestUtils.setField(buyer, "id", 200L);

        stranger = User.create("stranger@used-trade.com", "$2a$10$DUMMY", "외부인");
        ReflectionTestUtils.setField(stranger, "id", 999L);

        category = Category.create("전자기기", 1);
        ReflectionTestUtils.setField(category, "id", 1L);

        product = Product.create(seller, category, "아이폰 15", "박스 미개봉", 1_200_000L);
        ReflectionTestUtils.setField(product, "id", 10L);
    }

    private ChatRoom makeRoom(long id) {
        ChatRoom room = ChatRoom.create(product, buyer);
        ReflectionTestUtils.setField(room, "id", id);
        return room;
    }

    @Nested
    @DisplayName("createOrGetRoom()")
    class CreateOrGetRoom {

        @Test
        @DisplayName("기존 방이 없으면 새로 만들어 저장한다")
        void createsNewRoomIfAbsent() {
            given(userRepository.findById(200L)).willReturn(Optional.of(buyer));
            given(productRepository.findById(10L)).willReturn(Optional.of(product));
            given(chatRoomRepository.findByProductIdAndBuyerId(10L, 200L)).willReturn(Optional.empty());
            given(chatRoomRepository.save(any(ChatRoom.class))).willAnswer(inv -> {
                ChatRoom r = inv.getArgument(0);
                ReflectionTestUtils.setField(r, "id", 7L);
                return r;
            });

            ChatRoomResponse response = chatService.createOrGetRoom(200L, 10L);

            ArgumentCaptor<ChatRoom> captor = ArgumentCaptor.forClass(ChatRoom.class);
            verify(chatRoomRepository).save(captor.capture());
            assertThat(captor.getValue().getProduct()).isSameAs(product);
            assertThat(captor.getValue().getBuyer()).isSameAs(buyer);
            assertThat(response.id()).isEqualTo(7L);
            assertThat(response.productId()).isEqualTo(10L);
            assertThat(response.buyerId()).isEqualTo(200L);
            assertThat(response.sellerId()).isEqualTo(100L);
        }

        @Test
        @DisplayName("기존 방이 있으면 새로 만들지 않고 그대로 반환 — 중복 방 방지")
        void reusesExistingRoom() {
            ChatRoom existing = makeRoom(7L);
            given(userRepository.findById(200L)).willReturn(Optional.of(buyer));
            given(productRepository.findById(10L)).willReturn(Optional.of(product));
            given(chatRoomRepository.findByProductIdAndBuyerId(10L, 200L)).willReturn(Optional.of(existing));

            ChatRoomResponse response = chatService.createOrGetRoom(200L, 10L);

            verify(chatRoomRepository, never()).save(any());
            assertThat(response.id()).isEqualTo(7L);
        }

        @Test
        @DisplayName("buyer 가 DB 에 없으면 USER_NOT_FOUND")
        void buyerMissing_throws() {
            given(userRepository.findById(200L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> chatService.createOrGetRoom(200L, 10L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.USER_NOT_FOUND);

            verify(productRepository, never()).findById(any());
        }

        @Test
        @DisplayName("product 가 DB 에 없으면 PRODUCT_NOT_FOUND")
        void productMissing_throws() {
            given(userRepository.findById(200L)).willReturn(Optional.of(buyer));
            given(productRepository.findById(10L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> chatService.createOrGetRoom(200L, 10L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.PRODUCT_NOT_FOUND);
        }

        @Test
        @DisplayName("seller 가 본인 상품에 채팅 시도 → CHAT_SELF_NOT_ALLOWED — 도메인 가드 위임")
        void selfChat_throws() {
            given(userRepository.findById(100L)).willReturn(Optional.of(seller));
            given(productRepository.findById(10L)).willReturn(Optional.of(product));
            given(chatRoomRepository.findByProductIdAndBuyerId(10L, 100L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> chatService.createOrGetRoom(100L, 10L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.CHAT_SELF_NOT_ALLOWED);

            verify(chatRoomRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("sendMessage()")
    class SendMessage {

        @Test
        @DisplayName("buyer 가 자기 방에 발화 → 메시지 저장되고 응답 반환")
        void byBuyer_success() {
            ChatRoom room = makeRoom(1L);
            given(userRepository.findById(200L)).willReturn(Optional.of(buyer));
            given(chatRoomRepository.findById(1L)).willReturn(Optional.of(room));
            given(messageRepository.save(any(Message.class))).willAnswer(inv -> {
                Message m = inv.getArgument(0);
                ReflectionTestUtils.setField(m, "id", 1000L);
                return m;
            });

            MessageResponse response = chatService.sendMessage(200L, 1L, "안녕하세요");

            assertThat(response.id()).isEqualTo(1000L);
            assertThat(response.roomId()).isEqualTo(1L);
            assertThat(response.senderId()).isEqualTo(200L);
            assertThat(response.content()).isEqualTo("안녕하세요");
            assertThat(response.type()).isEqualTo(MessageType.TEXT);
        }

        @Test
        @DisplayName("비참여자 발화는 NOT_CHAT_PARTICIPANT — 도메인 가드 위임")
        void byStranger_throws() {
            ChatRoom room = makeRoom(1L);
            given(userRepository.findById(999L)).willReturn(Optional.of(stranger));
            given(chatRoomRepository.findById(1L)).willReturn(Optional.of(room));

            assertThatThrownBy(() -> chatService.sendMessage(999L, 1L, "끼어들기"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.NOT_CHAT_PARTICIPANT);

            verify(messageRepository, never()).save(any());
        }

        @Test
        @DisplayName("방이 없으면 CHAT_ROOM_NOT_FOUND")
        void roomMissing_throws() {
            given(userRepository.findById(200L)).willReturn(Optional.of(buyer));
            given(chatRoomRepository.findById(1L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> chatService.sendMessage(200L, 1L, "ping"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.CHAT_ROOM_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("listMessages() — 커서 페이징")
    class ListMessages {

        @Test
        @DisplayName("정상: 참여자가 size+1 트릭으로 hasNext / nextCursor 를 정확히 받는다")
        void participant_paging_works() {
            ChatRoom room = makeRoom(1L);
            // size=2 요청 → repo 는 size+1=3 행 반환 → hasNext=true, items=2
            Message m3 = Message.send(room, buyer, "msg3", MessageType.TEXT);
            ReflectionTestUtils.setField(m3, "id", 30L);
            Message m2 = Message.send(room, seller, "msg2", MessageType.TEXT);
            ReflectionTestUtils.setField(m2, "id", 20L);
            Message m1 = Message.send(room, buyer, "msg1", MessageType.TEXT);
            ReflectionTestUtils.setField(m1, "id", 10L);

            given(chatRoomRepository.findById(1L)).willReturn(Optional.of(room));
            given(messageRepository.findByRoomCursor(eq(1L), eq(null), any(Pageable.class)))
                    .willReturn(List.of(m3, m2, m1));

            MessageCursorPageResponse response = chatService.listMessages(200L, 1L, null, 2);

            assertThat(response.items()).hasSize(2);
            assertThat(response.items().get(0).id()).isEqualTo(30L);
            assertThat(response.items().get(1).id()).isEqualTo(20L);
            assertThat(response.hasNext()).isTrue();
            assertThat(response.nextCursor()).isEqualTo(20L);
        }

        @Test
        @DisplayName("size 가 0 또는 음수면 1로 클램핑")
        void size_clampsToMin() {
            ChatRoom room = makeRoom(1L);
            given(chatRoomRepository.findById(1L)).willReturn(Optional.of(room));
            given(messageRepository.findByRoomCursor(eq(1L), eq(null), any(Pageable.class)))
                    .willReturn(List.of());

            chatService.listMessages(200L, 1L, null, 0);

            ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
            verify(messageRepository).findByRoomCursor(eq(1L), eq(null), pageableCaptor.capture());
            // size=0 → MIN(1) → +1 = 2
            assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(2);
        }

        @Test
        @DisplayName("size 가 100 초과면 100으로 클램핑")
        void size_clampsToMax() {
            ChatRoom room = makeRoom(1L);
            given(chatRoomRepository.findById(1L)).willReturn(Optional.of(room));
            given(messageRepository.findByRoomCursor(eq(1L), eq(null), any(Pageable.class)))
                    .willReturn(List.of());

            chatService.listMessages(200L, 1L, null, 999);

            ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
            verify(messageRepository).findByRoomCursor(eq(1L), eq(null), pageableCaptor.capture());
            // 100 클램핑 → +1 = 101
            assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(101);
        }

        @Test
        @DisplayName("비참여자 조회는 NOT_CHAT_PARTICIPANT")
        void byStranger_throws() {
            ChatRoom room = makeRoom(1L);
            given(chatRoomRepository.findById(1L)).willReturn(Optional.of(room));

            assertThatThrownBy(() -> chatService.listMessages(999L, 1L, null, 20))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.NOT_CHAT_PARTICIPANT);

            verify(messageRepository, never()).findByRoomCursor(any(), any(), any());
        }
    }

    private static <T> T eq(T value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }
}
