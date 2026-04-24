/**
 * Cross-cutting 공통 영역.
 *
 * <p>모든 도메인에서 공유하는 인프라성 코드를 모아둔다.
 * <ul>
 *   <li>{@code config}    - Security, Redis, JPA, WebSocket 전역 설정</li>
 *   <li>{@code exception} - 공통 예외 + {@code @RestControllerAdvice} 글로벌 핸들러</li>
 *   <li>{@code response}  - 표준 응답 래퍼 ({@code ApiResponse})</li>
 *   <li>{@code domain}    - BaseEntity, 공통 Enum 등 도메인 무관 타입</li>
 * </ul>
 *
 * <p><b>의존성 규칙:</b> common 패키지는 어떤 도메인 패키지도 import 하지 않는다.
 * (역방향 의존 금지 — 도메인 → common 은 허용, common → 도메인 은 금지)
 */
package com.portfolio.used_trade.common;
