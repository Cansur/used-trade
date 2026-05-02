package com.portfolio.used_trade.user.dto;

import com.portfolio.used_trade.user.domain.Role;
import com.portfolio.used_trade.user.domain.User;

import java.time.LocalDateTime;

/**
 * 사용자 정보 응답 DTO.
 *
 * <p><b>의도적으로 빠진 필드</b>
 * <ul>
 *   <li>{@code password}        — 응답에 절대 포함 금지 (DTO 분리의 가장 큰 이유)</li>
 *   <li>{@code status}          — 내부 운영 정보. 클라이언트가 알 필요 X</li>
 *   <li>{@code updatedAt}       — 가입자 정보에서는 의미가 약함</li>
 * </ul>
 *
 * <p>Entity → DTO 변환은 {@link #from(User)} 정적 팩토리에서. Entity 가 DTO 를
 * 알게 되는 역방향 의존을 피한다.
 */
public record UserResponse(
        Long id,
        String email,
        String nickname,
        Role role,
        LocalDateTime createdAt
) {

    /** Entity → DTO. */
    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getNickname(),
                user.getRole(),
                user.getCreatedAt()
        );
    }
}
