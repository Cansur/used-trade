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
 * {@link ChatRoom} 도메인 단위 테스트.
 *
 * <p>회귀 박는 의도:
 * <ul>
 *   <li>create() 정상 경로의 필드 할당</li>
 *   <li>본인 상품 차단 가드 — {@link ErrorCode#CHAT_SELF_NOT_ALLOWED}</li>
 *   <li>isParticipant — buyer / seller 양쪽 모두 true, 그 외는 false</li>
 *   <li>getSellerId — product.seller 위임</li>
 * </ul>
 */
@DisplayName("ChatRoom 도메인 단위 테스트")
class ChatRoomTest {

    private User seller;
    private User buyer;
    private Category category;
    private Product product;

    @BeforeEach
    void setUp() {
        seller = User.create("seller@used-trade.com", "$2a$10$DUMMY", "판매자");
        ReflectionTestUtils.setField(seller, "id", 100L);

        buyer = User.create("buyer@used-trade.com", "$2a$10$DUMMY", "구매자");
        ReflectionTestUtils.setField(buyer, "id", 200L);

        category = Category.create("전자기기", 1);
        ReflectionTestUtils.setField(category, "id", 1L);

        product = Product.create(seller, category, "아이폰 15", "박스 미개봉", 1_200_000L);
        ReflectionTestUtils.setField(product, "id", 10L);
    }

    @Nested
    @DisplayName("정적 팩토리 create()")
    class Factory {

        @Test
        @DisplayName("정상: product 와 buyer 를 담아 인스턴스를 만든다")
        void create_assignsFields() {
            ChatRoom room = ChatRoom.create(product, buyer);

            assertThat(room.getProduct()).isSameAs(product);
            assertThat(room.getBuyer()).isSameAs(buyer);
        }

        @Test
        @DisplayName("판매자 본인이 자기 상품에 채팅을 시도하면 CHAT_SELF_NOT_ALLOWED")
        void create_selfChat_throws() {
            assertThatThrownBy(() -> ChatRoom.create(product, seller))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.CHAT_SELF_NOT_ALLOWED);
        }
    }

    @Nested
    @DisplayName("참여자 검증 isParticipant()")
    class Participants {

        @Test
        @DisplayName("buyer 본인의 id 는 true")
        void isParticipant_buyer() {
            ChatRoom room = ChatRoom.create(product, buyer);
            assertThat(room.isParticipant(200L)).isTrue();
        }

        @Test
        @DisplayName("product.seller 의 id 도 true — 별도 컬럼 없이 product 에서 파생 검증")
        void isParticipant_seller() {
            ChatRoom room = ChatRoom.create(product, buyer);
            assertThat(room.isParticipant(100L)).isTrue();
        }

        @Test
        @DisplayName("그 외 사용자는 false")
        void isParticipant_other() {
            ChatRoom room = ChatRoom.create(product, buyer);
            assertThat(room.isParticipant(999L)).isFalse();
        }
    }

    @Nested
    @DisplayName("판매자 id 헬퍼 getSellerId()")
    class SellerIdHelper {

        @Test
        @DisplayName("product.seller.id 를 그대로 반환한다")
        void getSellerId_delegatesToProduct() {
            ChatRoom room = ChatRoom.create(product, buyer);
            assertThat(room.getSellerId()).isEqualTo(100L);
        }
    }
}
