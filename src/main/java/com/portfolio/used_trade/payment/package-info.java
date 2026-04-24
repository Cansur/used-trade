/**
 * 결제 도메인.
 *
 * <p>실제 PG 연동은 범위 밖 — <b>Mock Payment</b> 로 대체하여 Saga 플로우만 검증.
 *
 * <p><b>주요 책임</b>
 * <ul>
 *   <li>결제 요청 수신 (trade 도메인이 오케스트레이터)</li>
 *   <li>Mock 결제 처리: 랜덤 성공/실패로 보상 트랜잭션 흐름 시연</li>
 *   <li>결제 결과 이벤트 발행 (SUCCESS / FAILED)</li>
 * </ul>
 *
 * <p><b>왜 Mock 인가?</b>
 * <ul>
 *   <li>포트폴리오 핵심은 "분산 트랜잭션 + 보상 패턴"이지 PG 연동이 아님</li>
 *   <li>실제 PG (토스/카카오) 연동은 비즈니스 가치가 높지만 학습 가치는 낮음</li>
 *   <li>Mock 으로 실패 시나리오를 원하는 빈도로 제어 → Saga 롤백 검증 용이</li>
 * </ul>
 */
package com.portfolio.used_trade.payment;
