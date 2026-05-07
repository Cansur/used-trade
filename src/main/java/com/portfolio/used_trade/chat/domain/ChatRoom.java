package com.portfolio.used_trade.chat.domain;

import com.portfolio.used_trade.common.domain.BaseEntity;
import com.portfolio.used_trade.common.exception.BusinessException;
import com.portfolio.used_trade.common.exception.ErrorCode;
import com.portfolio.used_trade.product.domain.Product;
import com.portfolio.used_trade.user.domain.User;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 채팅방 엔티티.
 *
 * <p><b>설계 원칙</b>
 * <ul>
 *   <li>setter 미노출 — 변경은 의도 메서드로만</li>
 *   <li>(product, buyer) 유일 — 동일 buyer 가 동일 상품에 두 번째 채팅방을 만들지 못하게
 *       UNIQUE 제약. seller 는 product.seller 로 파생.</li>
 *   <li>본인 상품에 채팅 차단 — {@link #create} 에서 가드 ({@link ErrorCode#CHAT_SELF_NOT_ALLOWED})</li>
 * </ul>
 *
 * <p><b>관계</b>
 * <ul>
 *   <li>{@code product} : Product 단방향 ManyToOne (LAZY)</li>
 *   <li>{@code buyer}   : User 단방향 ManyToOne (LAZY) — 채팅 시작자(구매 의향자)</li>
 *   <li>판매자(seller) 는 {@code product.getSeller()} 로 파생 — 별도 컬럼 두지 않는다.</li>
 * </ul>
 *
 * <p><b>인덱스</b>
 * <ul>
 *   <li>{@code uk_chat_rooms_product_buyer} : (product_id, buyer_id) UNIQUE — 중복 방 차단</li>
 *   <li>{@code idx_chat_rooms_buyer}        : 내 채팅방 목록 (구매자 시점)</li>
 *   <li>{@code idx_chat_rooms_product}      : 상품별 채팅방 목록 (판매자 시점에 product join 으로 접근)</li>
 * </ul>
 */
@Entity
@Table(
        name = "chat_rooms",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_chat_rooms_product_buyer",
                        columnNames = {"product_id", "buyer_id"}
                )
        },
        indexes = {
                @Index(name = "idx_chat_rooms_buyer", columnList = "buyer_id"),
                @Index(name = "idx_chat_rooms_product", columnList = "product_id")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatRoom extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "buyer_id", nullable = false)
    private User buyer;

    // ---------- 생성 ----------

    /**
     * 채팅방 정적 팩토리.
     *
     * <p>본인 상품에 자기 자신과 채팅을 시작할 수 없다 → {@link ErrorCode#CHAT_SELF_NOT_ALLOWED}.
     * (product, buyer) 중복은 DB UNIQUE 가 막고, 서비스 레이어가 existsBy 로 우선 확인해
     * {@link DataIntegrityViolationException} 보다 친화적인 응답을 만든다.
     */
    public static ChatRoom create(Product product, User buyer) {
        if (product.isOwnedBy(buyer.getId())) {
            throw new BusinessException(ErrorCode.CHAT_SELF_NOT_ALLOWED);
        }
        ChatRoom room = new ChatRoom();
        room.product = product;
        room.buyer = buyer;
        return room;
    }

    // ---------- 참여자 검증 ----------

    /**
     * 채팅방 참여자 (buyer 본인 또는 product.seller) 인지.
     */
    public boolean isParticipant(Long userId) {
        return this.buyer.getId().equals(userId) || this.product.isOwnedBy(userId);
    }

    /** 판매자 id 노출 헬퍼 — Service / DTO 변환에서 LAZY 접근을 명시적으로 묶는다. */
    public Long getSellerId() {
        return this.product.getSeller().getId();
    }
}
