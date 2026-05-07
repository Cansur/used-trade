package com.portfolio.used_trade.chat.repository;

import com.portfolio.used_trade.chat.domain.Message;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 메시지 영속성 어댑터.
 *
 * <p>제공:
 * <ul>
 *   <li>{@link JpaRepository} 기본 CRUD</li>
 *   <li>{@link #findByRoomCursor} — 방 안에서 id 내림차순 (최신순) 커서 페이징</li>
 * </ul>
 */
public interface MessageRepository extends JpaRepository<Message, Long> {

    /**
     * 채팅방 메시지를 id DESC (최신순) 으로 페이징 조회. 커서 기반.
     *
     * <p>인덱스 {@code idx_messages_room_id (chat_room_id, id)} 가 직접 지원.
     * sender 의 LAZY 연관도 JOIN FETCH 로 한 번에 펼친다 (DTO 변환에서 nickname 사용).
     *
     * <p><b>cursor 의미</b>
     * <ul>
     *   <li>{@code null} — 첫 페이지 (가장 최신부터)</li>
     *   <li>그 외 — {@code id < cursor} (앞 페이지의 마지막 id)</li>
     * </ul>
     *
     * <p><b>호출자 약속</b> Pageable.size 는 "페이지 크기 + 1" 로 — 호출자가 size+1 째 행의
     * 존재로 hasNext 를 판정한다. (ProductService.list 와 동일 패턴)
     */
    @Query("""
            SELECT m FROM Message m
            JOIN FETCH m.sender
            WHERE m.chatRoom.id = :roomId
              AND (:cursor IS NULL OR m.id < :cursor)
            ORDER BY m.id DESC
            """)
    List<Message> findByRoomCursor(
            @Param("roomId") Long roomId,
            @Param("cursor") Long cursor,
            Pageable pageable
    );
}
