# TODO

> 다음에 할 일. AI 는 "진행 중" → "대기" 순서로 집어 간다.
> **체크 규칙: 완료 근거 (무엇을, 어디에 — 파일, 커밋, PR, 테스트명, 측정값) 가 없으면 체크하지 않는다.**
> 형식: `- [x] **ID** 내용 — 근거: ...`

<br>

## 진행 중

- [ ] **WIKI-4** Obsidian 에서 `docs/` 폴더를 볼트로 열기 + 설정 → 파일 및 링크 → "[[위키링크]] 사용" 끄기 (사람)


<br>

## 대기

### M1. 명세 — 무엇을 만들지 확정


### M2. 상품 등록 AI 보조 — 구현

> SPEC-1 이후 확정. 아래는 초안이며 명세에 따라 쪼개거나 바뀐다.

- [ ] **DEV-1** `assist` 도메인 패키지 + `LlmClientPort` + 실제 어댑터 + Mock 어댑터
- [ ] **DEV-2** 테스트용 `ControllableLlmClient` — 지연 / 타임아웃 / 깨진 응답 주입
- [ ] **DEV-3** 응답 검증 — JSON 스키마 + categoryId 가 DB 활성 카테고리인지
- [ ] **DEV-4** 타임아웃 + 재시도 + 서킷브레이커 (Resilience4j 검토)
- [ ] **DEV-5** 폴백 — 키워드 규칙 카테고리 + DB 기반 가격 범위. 겹침 규칙은 [ADR-007](adr/007-listing-assist.md) 4절. 함께 정할 것: 매칭 범위 (제목만 / 제목 + 설명), 공백 처리 ("iPhone 15" 미매칭), 일반적인 키워드 정리 ("세트" 때문에 "레고 세트" → `null`), 품목 없는 키워드 ("아이폰" 단독 미매칭)
- [ ] ~~**DEV-6** 비동기 추천 흐름 (요청 → 결과 전달)~~ — 취소: 동기 + 타임아웃 + 폴백으로 결정 (assist-spec 2-1)
- [ ] **DEV-12** 동기 추천 API + 가상 스레드 활성화 (`spring.threads.virtual.enabled`)
- [ ] **DEV-7** Redis 사용자별 호출 제한
- [ ] **DEV-8** Redis 입력 캐싱 (정규화한 입력의 해시를 키로)
- [ ] **DEV-9** 가격 범위 계산 — 같은 카테고리·유사 상품의 중앙값 / 범위
- [ ] **DEV-10** 지표 기록 — 지연, 성공/실패/폴백 수, 토큰·건당 비용, 캐시 적중 (Micrometer)
- [ ] **DEV-11** 상품 등록 API 와 연결 — AI 실패와 무관하게 등록 성공

### M3. 측정

- [ ] **MEAS-1** 장애 주입 부하 테스트 — 타임아웃·깨진 응답 비율별 등록 성공률, p95
- [ ] **MEAS-2** 정확도 평가셋 (정답 라벨 상품 100~200개) + 카테고리 정답률. 제목 + 설명 / 제목만 두 조건으로 비교 ([assist-spec](specs/assist-spec.md) 2-2)
- [ ] **MEAS-3** 캐시 적중률 / 건당 비용 / 호출 제한 동작 측정
- [ ] **MEAS-4** README 측정표 + ADR-007 결과 반영
- [ ] **MEAS-5** 실제 LLM 타임아웃 후 재시도 성공률 — 타임아웃 난 요청을 즉시 1회 재시도했을 때 예산 안에 성공하는 비율. 결과로 assist-spec 2-4 재시도 정책 확정. DEV-1 (실제 어댑터) 이후 진행
  - 조건 기록 필수: 모델, 입력 길이, 타임아웃 값, 표본 수, 측정 시각대

### M4. 작업 AI / 검수 AI 분리

> 추가 지출 0원. 평소 검수 = Claude 새 맥락 에이전트, 실험 = 웹 Gemini (Plus).

- [ ] **FLOW-1** [ai-workflow.md](ai-workflow.md) 대로 M2 개발에 새 맥락 검수 에이전트 적용, 검수 기록 남기기
- [ ] **FLOW-2a** 버그를 심은 diff 약 20개 + 공통 검수 지시문 준비
- [ ] **FLOW-2b** C1 자기 검수 / C2 새 맥락 Claude / C3 웹 Gemini 로 같은 diff 검수, 발견·누락·오탐 기록
- [ ] **FLOW-3** 실험 결과 1페이지 (표본 크기, 한계 포함)

### M5. 마무리

- [ ] **DOC-1** README 갱신 (기술 스택, 측정표, 구조도)
- [ ] **DOC-2** `CLAUDE.md` 현재 위치 갱신
- [ ] **DOC-3** 면접 대비 Q&A (ADR-007 끝)

<br>

### M6. 결제 완성 — PG 연동 + Outbox (Phase 3, 검토 단계)

> 둘은 묶여 있다. 실제 PG 를 붙이면 "돈은 나갔는데 DB 반영 실패 (T2)" 가 진짜 문제가 되고, 그걸 Outbox 가 해결한다.

- [ ] **TRADE-1** 거래 마무리 API — `settle` (인수 완료 → 상품 SOLD), `cancel`, 내 거래 목록. 도메인 메서드 `Trade.settle()` 은 있으나 호출하는 API 가 없음 (`TradeController` 주석에 "다음 PR" 로 남아 있음)
- [ ] **PG-1** PG 후보 검토 — 테스트 모드로 실결제 없이 연동 가능한 곳 (예: 토스페이먼츠, 포트원) 비교
- [ ] **PG-2** 실제 PG 어댑터 — `PaymentGatewayPort` 구현 추가, Mock 은 유지. 참고: `MockPaymentGateway` 는 `@Profile("!prod")` 라 `prod` 프로파일에는 구현체가 없음 (현재 AWS 배포는 `docker` 프로파일이라 영향 없음)
- [ ] **PG-3** 결제 승인 콜백 / 웹훅 검증 + 멱등성 (중복 승인 방지)
- [ ] **OUTBOX-1** ADR-008 — Outbox 도입 여부와 범위 (T2 실패, 크래시, 보상 실패 중 무엇을 자동화할지)
- [ ] **OUTBOX-2** Outbox 테이블 + 릴레이 (발행 / 재시도 / 실패 처리)
- [ ] **OUTBOX-3** 장애 주입 테스트 — T2 실패, 크래시 상황에서 자동 복구 입증 + ADR-5 한계 표 갱신

### 조사

- [ ] **INV-1** 동시 예약 데드락 원인 조사 — 재시도의 약 80% 가 버전 충돌이 아닌 데드락 (`CannotAcquireLockException`), 원인 미확인 ([ADR-2](adr/002-optimistic-locking.md) 에러 상황 3). `SHOW ENGINE INNODB STATUS` 로 확인

### M7. AWS 재배포 (마지막)

- [ ] **AWS-1** [deploy/aws-setup.md](deploy/aws-setup.md) 다시 익히기 — Step 7+8 재실행 (~15분)
- [ ] **AWS-2** Phase 2·3 에서 추가된 설정 (LLM API 키, PG 키 등) 을 배포 환경 변수에 반영
- [ ] **AWS-3** 면접 직전 재배포 + 시연 확인 → 끝나면 cleanup

<br>

## 완료

- [x] **SPEC-3** QA 테스트 리스트 — 근거: [specs/assist-qa.md](specs/assist-qa.md) A~L 12개 그룹. 2026-11-08 미결 2건 결정: 제목만 허용 ([assist-spec](specs/assist-spec.md) 2-2, QA A3), 키워드 겹침은 최장 키워드 → 일치 수 → 동률 `null` ([ADR-007](adr/007-listing-assist.md) 4절, QA F3·F5·F6). `price-anchors.csv` 시뮬레이션으로 규칙 확인, 이때 QA F1 오류 ("아이폰" 단독은 매칭 안 됨) 수정

- [x] **ARCH-2** trade ↔ payment 패키지 순환 해소 + 순환 금지 규칙 — 근거: PR #14 (`d1e41e7`). `saga` 패키지 분리, `ArchitectureTest.noCyclesBetweenTopLevelPackages` 이동 전 실패 / 이동 후 통과, 전체 160 / 0, 확정 API HTTP 확인 (401 / 403 / 200 / 404), [ADR-1](adr/001-modular-monolith.md) 에러 상황 2
- [x] **ARCH-1** 도메인 경계 정리 — 근거: PR #13 (`3b72ab1`). 다른 도메인 Repository 주입 제거, 상품 상태 전이를 `ProductService` 로 이동 (`Propagation.MANDATORY`), ArchUnit 규칙 4개, `TradeServiceAdviceOrderTest`. 전체 156 / 0, [ADR-1](adr/001-modular-monolith.md) 에러 상황 3·4, [ADR-2](adr/002-optimistic-locking.md) 에러 상황 3

- [x] **SPEC-2** ADR-007 초안 — 근거: [adr/007-listing-assist.md](adr/007-listing-assist.md) (Proposed). 별도 `assist` 도메인 + product 조회 Service, `LlmClientPort` (실패 종류별 sealed 결과), 폴백 표, Redis 키 2종 (분 단위 고정 윈도우 / 완전 일치 캐시 v1), 가격 신뢰도. 2026-10-30 사람 검토 완료

- [x] **SPEC-4** 가격 기준값 조사 (사람) — 근거: `src/main/resources/seed/price-anchors.csv` 60줄 중 56줄 가격 기입 (2026-10-30, 당근마켓 매물 수동 열람, URL·판매자 정보 미기록). 빈 4줄 (말름 서랍장, 콘서트 티켓, LP, 모바일 상품권) 은 note 에 사유 기록, 생성기가 건너뜀 ([assist-spec](specs/assist-spec.md) 2-6)

- [x] **SPEC-1** 요청 명세서 작성 — 근거: [specs/assist-spec.md](specs/assist-spec.md) 2-1 ~ 2-6 (동기 + 타임아웃 + 폴백 + 가상 스레드 / 입력 제목·설명 / Haiku 5.5 / 3초 예산 + 조건부 1회 재시도 (MEAS-5 후 최종) / 평가셋 100개 / 수작업 기준값 + 합성 시드). 2026-10-29 인터뷰. DEV-6 취소 + DEV-12, MEAS-5, SPEC-4 파생

- [x] **LOCAL-1** Docker Desktop 실행 → `docker compose up -d` — 근거: 2026-10-26 `usedtrade-mysql`, `usedtrade-redis` 컨테이너 Started
- [x] **LOCAL-2** `./gradlew bootRun` + 헬스체크 — 근거: `GET /api/hello/health-db` → `{"mysql":"UP","redis":"UP"}`
- [x] **LOCAL-3** 테스트 재확인 — 근거: `./gradlew test --rerun` → 138 / 실패 0 / 에러 0 / 스킵 0 (2026-10-26 16:43 KST). 코드 변경 없이 그냥 `test` 를 돌리면 Gradle 이 UP-TO-DATE 로 건너뛰므로 강제 재실행함
- [x] **LOCAL-4** API 흐름 수동 확인 — 근거: 가입(201) → 로그인(200) → `/users/me`(200) → 상품 등록(201) → 목록(200) → 채팅방(201) → 예약 RESERVED(201) → 확정 CONFIRMED + 결제(200) → 상품 상태 TRADING. Postman 컬렉션과 같은 엔드포인트를 스크립트로 호출. 확정 후에도 SOLD 가 아닌 이유는 settle API 미구현 → TRADE-1
- [x] **LOCAL-5** 막힌 부분 기록 — 근거: [status.md](status.md) 변경 기록 (막힌 곳 없음, 주의점 2개)

- [x] **WIKI-1** 위키 입구 문서 — 근거: `docs/index.md`
- [x] **WIKI-2** 목표·현황·TODO 문서 — 근거: `docs/roadmap.md`, `docs/status.md`, `docs/todo.md`
- [x] **WIKI-3** 작업 규칙 문서 + CLAUDE.md 진입 규칙 연결 — 근거: `docs/policies.md`, `docs/ai-workflow.md`, `CLAUDE.md` "현재 위치"
