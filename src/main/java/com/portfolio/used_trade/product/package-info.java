/**
 * 상품 도메인.
 *
 * <p>판매자가 등록한 중고 상품을 관리한다.
 *
 * <p><b>주요 책임</b>
 * <ul>
 *   <li>상품 등록 / 수정 / 삭제 (판매자 본인만 가능)</li>
 *   <li>상품 목록 조회 (페이지네이션 + 검색 + 정렬)</li>
 *   <li>상품 상태 관리: {@code SELLING → RESERVED → SOLD}</li>
 *   <li>이미지 업로드 (Phase 2: S3)</li>
 * </ul>
 *
 * <p><b>다른 도메인과의 관계</b>
 * <ul>
 *   <li>trade 에서 상품 상태를 {@code RESERVED / SOLD} 로 전이시킴 (Saga 핵심)</li>
 *   <li>동시에 두 명이 결제 시도 시 상태 충돌 → 낙관적 락 (ADR-2) 시연 포인트</li>
 * </ul>
 */
package com.portfolio.used_trade.product;
