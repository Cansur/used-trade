package com.portfolio.used_trade.chat.dto;

import java.util.List;

/**
 * 메시지 커서 페이징 응답. ProductCursorPageResponse 와 동일한 형태로 일관성 유지.
 *
 * <p>{@code nextCursor} 는 다음 페이지 진입 시 그대로 query param 으로 전달.
 * {@code hasNext} 는 size+1 트릭으로 정확히 판정한 결과.
 */
public record MessageCursorPageResponse(
        List<MessageResponse> items,
        Long nextCursor,
        boolean hasNext
) {
}
