/**
 * 사용자(회원) 도메인.
 *
 * <p>회원가입 · 로그인 · 프로필 · JWT 발급/갱신을 담당.
 *
 * <p><b>주요 책임</b>
 * <ul>
 *   <li>이메일/비밀번호 기반 회원가입 (BCrypt 해싱)</li>
 *   <li>JWT Access Token 발급 + Refresh Token (Redis 보관)</li>
 *   <li>로그아웃 시 Access Token 블랙리스트 (Redis TTL)</li>
 *   <li>프로필 조회/수정</li>
 * </ul>
 *
 * <p><b>다른 도메인과의 관계</b>
 * <ul>
 *   <li>product / trade / chat 은 user 의 식별자(userId)만 참조</li>
 *   <li>user 는 다른 도메인을 알지 못함 (단방향 의존)</li>
 * </ul>
 */
package com.portfolio.used_trade.user;
