package com.portfolio.used_trade.user.domain;

/**
 * 사용자 권한.
 *
 * <p>Phase 1 에서는 {@link #USER} 만 사용. Phase 2 에서 운영자/관리자 기능
 * 추가 시 {@link #ADMIN} 사용 예정.
 *
 * <p>Spring Security 가 권한 문자열을 다룰 때 관례로 {@code ROLE_} 접두사를
 * 요구한다. 그래서 Spring 으로 권한 객체를 만들 때는 {@code "ROLE_" + name()}
 * 를 사용한다 ({@code CustomUserDetails} 에서 처리 예정).
 */
public enum Role {
    USER,
    ADMIN
}
