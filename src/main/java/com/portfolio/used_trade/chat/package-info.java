/**
 * 채팅 도메인.
 *
 * <p>구매자-판매자 간 실시간 채팅. STOMP over WebSocket + Redis Pub/Sub.
 *
 * <p><b>주요 책임</b>
 * <ul>
 *   <li>채팅방 생성/조회 (상품 + 구매자 + 판매자 3자 관계)</li>
 *   <li>WebSocket 세션 관리 (JWT 인증, STOMP subscribe/send)</li>
 *   <li>메시지 저장 (MySQL) + 브로드캐스트 (Redis Pub/Sub)</li>
 *   <li>읽지 않은 메시지 카운트</li>
 * </ul>
 *
 * <p><b>시연 포인트 (ADR-3 연결)</b>
 * <ul>
 *   <li>단일 서버 WebSocket 한계 → 멀티 서버 시 같은 채팅방 사용자가 다른 서버에 붙으면?</li>
 *   <li>해결: Redis Pub/Sub 으로 서버 간 메시지 릴레이</li>
 *   <li>Before (단일서버): 2대 이상 띄우면 메시지 유실</li>
 *   <li>After (Redis Pub/Sub): N대 확장 시에도 메시지 일관성 유지</li>
 * </ul>
 */
package com.portfolio.used_trade.chat;
