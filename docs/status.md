# 진행 현황

> 무엇이 되어 있고, 무엇이 부족하고, 무엇이 언제 추가되었는가.
> 작업이 끝날 때마다 "변경 기록" 에 한 줄 추가하고, 기능·한계 표를 갱신한다.

<br>

## 기능 현황

| 도메인 | 기능 | 상태 | 근거 |
|---|---|---|---|
| user | 가입 / 로그인 / refresh / 로그아웃 (JWT) | ✅ 완료 | PR #1 |
| product | CRUD + 커서 페이징 + 이미지 (Mock 저장소) | ✅ 완료 | PR #2, ADR-4 |
| trade | 예약 (낙관적 락 + Spring Retry) | ✅ 완료 | PR #3, ADR-2 |
| chat | STOMP + Redis Pub/Sub 멀티 인스턴스 | ✅ 완료 | PR #4, #5, ADR-3 |
| payment | Mock PG + Saga 보상 (T1 실패만) | 🟡 부분 | PR #7, #9, ADR-5 |
| 구조 | 도메인 경계 + 순환 금지 (ArchUnit 5개), Saga 를 `saga` 패키지로 분리 | ✅ 완료 | PR #13, #14, ADR-1 |
| infra | Docker + AWS 실배포 (EC2×2, ALB, RDS, ElastiCache) | ✅ 완료 (현재 정지) | PR #6, #8, ADR-6 |
| infra | 로컬 실행 (compose + bootRun) | ✅ 확인 (2026-10-26) | [todo.md](todo.md) LOCAL-1~5 |
| docs | README + ADR 6편 + Guide 3편 | ✅ 완료 | PR #11, #12 |
| docs | 위키 (index / roadmap / status / todo / policies) | ✅ 완료 | 2026-10-26 |
| **assist** | **상품 등록 AI 보조** | ⬜ 예정 | [todo.md](todo.md) M1~M3 |
| process | 작업 AI / 검수 AI 분리 | ⬜ 예정 | [todo.md](todo.md) M4 |
| payment | 실제 PG (테스트 모드) 연동 + Outbox | ⬜ 검토 | [todo.md](todo.md) M6 |

<br>

## 측정 결과

| 주제 | 결과 | 근거 |
|---|---|---|
| 동시성 제어 | N=50 동시 예약, 중복 거래 3건 → 0건, p95 193ms | ADR-2 |
| 커서 페이징 | 10만 건, OFFSET 14.40ms → CURSOR 0.71ms (20.1배) | ADR-4 |
| 멀티 인스턴스 채팅 | 두 JVM 간 메시지 전달 | `ChatStompDualInstanceTest` |
| Saga 보상 | PG 실패 → trade.cancel + Product 복원 | `TradeSagaCompensationIT` |
| 테스트 | 단위 + 통합 160 / 0 실패 | `./gradlew test` |
| Retry·트랜잭션 순서 | Retry 안쪽 + `save()` : 충돌 재시도 0건, 충돌 예외 27건이 호출자까지 전달. 현재 순서 : 0건 | ADR-2 에러 상황 3 |

<br>

## 알려진 한계 / 부족한 점

| 항목 | 내용 | 대응 계획 |
|---|---|---|
| Saga 부분 구현 | T2 실패, 서버 크래시, 보상 실패는 운영자 수동 | Phase 3 — Outbox (todo M6) |
| Mock PG | 항상 성공. 실제 PG 어댑터 없음 | Phase 3 — 테스트 모드 PG 연동 (todo M6) |
| 거래 마무리 API 없음 | 확정(결제) 까지만 API 가 있음. settle / cancel / 내 거래 목록 미구현 → 상품이 API 로는 SOLD 가 되지 않고 TRADING 에 머묾 | todo TRADE-1 (2026-10-26 로컬 점검 중 발견) |
| 이미지 저장소 | Mock. 실제 S3 미연동 | 범위 밖 |
| 지표 수집 | Actuator 만 있음. Micrometer 지표 / 대시보드 없음 | Phase 2 A 에서 추가 (DEV 항목) |
| 가격 데이터 | 벤치마크용 테스트 데이터뿐, 현실적인 가격 분포 없음 | 수작업 기준값 + 합성 확장으로 결정 (assist-spec 2-6), 기준값 조사 SPEC-4 |
| 서킷브레이커 | 없음 (Spring Retry 만) | Phase 2 A 에서 추가 |
| 동시 예약 데드락 | 재시도의 약 80% 가 데드락. 재시도로 처리되지만 원인 미확인 | todo INV-1 |
| AWS | 비용 때문에 정지 상태. 재배포 ~15분 | 마지막 단계 (todo M7) |

<br>

## 변경 기록

최신이 위.

| 날짜 | 내용 |
|---|---|
| 2026-11-05 | **trade ↔ payment 순환 해소 (PR #14)** — 순환 금지 규칙을 넣자 실패해서 발견. Saga 를 `saga` 패키지로 분리, URL 유지. 테스트 160 / 0 |
| 2026-11-05 | **도메인 경계 정리 (PR #13)** — Repository 직접 주입 제거, 상품 상태 전이를 ProductService 로, ArchUnit 도입. "`save()` 는 재시도가 안 된다" 는 설명이 틀렸음을 실험으로 확인 (Retry 가 트랜잭션 바깥), 순서를 테스트로 고정. 테스트 156 / 0 |
| 2026-10-30 | **SPEC-2 완료** — ADR-007 초안 (Proposed). 측정 후 Accepted 예정 |
| 2026-10-30 | **SPEC-4 완료** — 가격 기준값 56/60줄 수작업 조사 (`seed/price-anchors.csv`). 빈 4줄은 생성기가 건너뜀. 범위 3배 초과 품목 다수 → ADR-007 에 "편차 크면 신뢰도 낮음" 규칙 반영 예정 |
| 2026-10-29 | **SPEC-1 완료** — [assist-spec.md](specs/assist-spec.md). 비동기 대신 동기 + 타임아웃 + 폴백 + 가상 스레드로 결정 (DEV-6 취소 → DEV-12). LLM Haiku 5.5, 3초 예산 + 조건부 재시도 (MEAS-5 로 검증 예정), 가격 시드는 공개 데이터 검토 후 수작업 기준값 + 합성으로 결정 (크롤링은 데이터베이스권 리스크) |
| 2026-10-26 | **로컬 실행 복구 완료 (Phase 1.5)** — compose → bootRun → 헬스체크 UP → 가입~결제 흐름 정상 → 테스트 138/0. 막힌 곳 없음. 발견: settle API 미구현 (TRADE-1). 주의: ① 코드 변경 없이 환경만 확인할 땐 `./gradlew test --rerun` (그냥 `test` 는 UP-TO-DATE 로 건너뜀) ② 로컬 DB 볼륨에 이전 벤치마크 상품 약 10만 건이 남아 있음 (새 상품 id 가 100067 부터) |
| 2026-10-26 | 검수 방식 결정 — 평소 Claude 새 맥락 에이전트, 실험만 웹 Gemini (Plus). Codex 미사용, 추가 지출 0원 |
| 2026-10-26 | 로드맵 확정 — 로컬 실행 복구 → AI 녹이기 → PG 연동 + Outbox → AWS 재배포 |
| 2026-10-26 | 위키 도입 (index, roadmap, status, todo, policies, ai-workflow) |
| 2026-05-15 | Phase 1 완료 — README / ADR / Guide 정리 (PR #11, #12) |
| 2026-05 | AWS 실배포, Saga, 결제, 채팅 멀티 인스턴스 (PR #5~#10) |
| 2026-04-25 | 프로젝트 시작 — user, product, trade (PR #1~#3) |
