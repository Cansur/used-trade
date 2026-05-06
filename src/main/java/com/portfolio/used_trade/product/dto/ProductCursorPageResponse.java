package com.portfolio.used_trade.product.dto;

import java.util.List;

/**
 * 커서 페이징 응답 DTO.
 *
 * <p><b>왜 nextCursor 와 hasNext 둘 다 두는가?</b>
 * 둘은 잉여처럼 보이지만 클라이언트 코드 단순성 향상:
 * <ul>
 *   <li>{@code hasNext} — 단순 boolean. UI 가 "더보기" 버튼 표시 여부 판정에 직접 사용</li>
 *   <li>{@code nextCursor} — 다음 요청에 그대로 실어 보낼 값. {@code hasNext == false} 면 {@code null}</li>
 * </ul>
 *
 * <p><b>nextCursor 결정 규칙</b>
 * <pre>
 *   hasNext = (조회된 행 수 == size + 1)
 *   nextCursor = hasNext ? 페이지 마지막 항목의 id : null
 * </pre>
 *
 * <p>id 내림차순(최신순) 정렬을 가정한다.
 */
public record ProductCursorPageResponse(
        List<ProductResponse> items,
        Long nextCursor,
        boolean hasNext
) {

    /** 빈 페이지. */
    public static ProductCursorPageResponse empty() {
        return new ProductCursorPageResponse(List.of(), null, false);
    }
}
