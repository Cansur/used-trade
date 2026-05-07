package com.portfolio.used_trade.chat.domain;

import com.portfolio.used_trade.common.exception.BusinessException;
import com.portfolio.used_trade.common.exception.ErrorCode;
import com.portfolio.used_trade.product.domain.Category;
import com.portfolio.used_trade.product.domain.Product;
import com.portfolio.used_trade.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link Message} 도메인 단위 테스트.
 *
 * <p>회귀 박는 의도:
 * <ul>
 *   <li>send() 정상 — 참여자(buyer/seller) 의 발화는 통과 + 필드 할당</li>
 *   <li>send() 가드 — 비참여자 발화는 {@link ErrorCode#NOT_CHAT_PARTICIPANT} 차단</li>
 * </ul>
 */
@DisplayName("Message 도메인 단위 테스트")
class MessageTest {

    private User seller;
    private User buyer;
    private User stranger;
    private ChatRoom room;

    @BeforeEach
    void setUp() {
        seller = User.create("seller@used-trade.com", "$2a$10$DUMMY", "판매자");
        ReflectionTestUtils.setField(seller, "id", 100L);

        buyer = User.create("buyer@used-trade.com", "$2a$10$DUMMY", "구매자");
        ReflectionTestUtils.setField(buyer, "id", 200L);

        stranger = User.create("stranger@used-trade.com", "$2a$10$DUMMY", "외부인");
        ReflectionTestUtils.setField(stranger, "id", 999L);

        Category category = Category.create("전자기기", 1);
        ReflectionTestUtils.setField(category, "id", 1L);

        Product product = Product.create(seller, category, "아이폰 15", "박스 미개봉", 1_200_000L);
        ReflectionTestUtils.setField(product, "id", 10L);

        room = ChatRoom.create(product, buyer);
        ReflectionTestUtils.setField(room, "id", 1L);
    }

    @Nested
    @DisplayName("정적 팩토리 send()")
    class Factory {

        @Test
        @DisplayName("buyer 발화 → 메시지가 생성되고 필드가 정확히 할당된다")
        void send_byBuyer_assignsFields() {
            Message message = Message.send(room, buyer, "안녕하세요", MessageType.TEXT);

            assertThat(message.getChatRoom()).isSameAs(room);
            assertThat(message.getSender()).isSameAs(buyer);
            assertThat(message.getContent()).isEqualTo("안녕하세요");
            assertThat(message.getType()).isEqualTo(MessageType.TEXT);
        }

        @Test
        @DisplayName("seller 발화도 통과 — product.seller 도 참여자")
        void send_bySeller_isAllowed() {
            Message message = Message.send(room, seller, "네 가능합니다", MessageType.TEXT);

            assertThat(message.getSender()).isSameAs(seller);
        }

        @Test
        @DisplayName("비참여자 (방의 buyer 도 product.seller 도 아닌) 발화는 NOT_CHAT_PARTICIPANT")
        void send_byStranger_throws() {
            assertThatThrownBy(() -> Message.send(room, stranger, "끼어들기", MessageType.TEXT))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.NOT_CHAT_PARTICIPANT);
        }
    }
}
