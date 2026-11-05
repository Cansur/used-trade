/**
 * 여러 도메인을 묶는 흐름의 조율자. 현재는 거래 확정 (결제 + 거래 상태 전환).
 *
 * <p>엔티티와 repository 를 두지 않고 각 도메인의 service 만 호출한다.
 * 의존 방향은 saga → trade, payment 이고, 어떤 도메인도 saga 에 의존하지 않는다 (ADR-1 에러 상황 2).
 */
package com.portfolio.used_trade.saga;
