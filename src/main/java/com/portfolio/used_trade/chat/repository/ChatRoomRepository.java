package com.portfolio.used_trade.chat.repository;

import com.portfolio.used_trade.chat.domain.ChatRoom;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 채팅방 영속성 어댑터.
 *
 * <p>제공:
 * <ul>
 *   <li>{@link JpaRepository} 기본 CRUD</li>
 *   <li>{@link #findByProductIdAndBuyerId} — 중복 채팅방 생성 회피용 lookup</li>
 *   <li>{@link #findRoomsForUser} — 내 채팅방 목록 (구매자 또는 판매자 시점, JOIN FETCH)</li>
 * </ul>
 */
public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {

    /**
     * 같은 product 에 같은 buyer 의 기존 방 조회. UNIQUE 제약과 짝.
     * 서비스가 중복 생성 시도 시 기존 방을 재사용한다.
     */
    Optional<ChatRoom> findByProductIdAndBuyerId(Long productId, Long buyerId);

    /**
     * 내 채팅방 목록 — 구매자 또는 판매자(product.seller) 어느 쪽이든 일치하면 포함.
     *
     * <p>JOIN FETCH 로 product / buyer / seller 의 LAZY 연관을 한 번에 펼친다 —
     * {@code spring.jpa.open-in-view=false} 환경에서 컨트롤러는 트랜잭션 밖이므로,
     * DTO 변환 시 LAZY 접근하면 LazyInitializationException.
     *
     * <p>정렬: 최신 활동 순으로 보여주는 게 자연스러우나 1차에는 단순 id DESC.
     * 향후 마지막 메시지 시각 기준 정렬은 별도 쿼리/projection 으로.
     */
    @Query("""
            SELECT r FROM ChatRoom r
            JOIN FETCH r.product p
            JOIN FETCH p.seller
            JOIN FETCH r.buyer
            WHERE r.buyer.id = :userId OR p.seller.id = :userId
            ORDER BY r.id DESC
            """)
    List<ChatRoom> findRoomsForUser(@Param("userId") Long userId);
}
