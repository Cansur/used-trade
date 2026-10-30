# used-trade 위키

> 프로젝트의 입구 문서. 사람(Obsidian)과 AI 모두 여기서 시작한다.
> 큰 그림과 구조만 적는다. 코드 상세는 코드와 `AI-NOTE` 주석이 맥락이다.

<br>

## 읽는 순서

**AI (새 세션)**
1. [roadmap.md](roadmap.md) — 지금 무엇을 왜 하는가
2. [todo.md](todo.md) — 다음에 할 일 (진행 중 → 대기 순서)
3. [policies.md](policies.md) — 작업 규칙 (특히 TODO 체크 규칙, 문서 갱신 규칙)
4. 작업 대상 도메인의 ADR / Guide

**사람 (진행 확인)**
- [status.md](status.md) — 완료된 것 / 부족한 것 / 변경 기록
- [todo.md](todo.md) — 진행 중인 것 / 남은 것

<br>

## 문서 지도

| 분류 | 문서 | 내용 |
|---|---|---|
| 방향 | [roadmap.md](roadmap.md) | 목표, 방향성 원칙, 하지 않을 것 |
| 진행 | [status.md](status.md) | 기능 현황, 알려진 한계, 변경 기록 |
| 진행 | [todo.md](todo.md) | 작업 목록 (ID + 완료 근거) |
| 규칙 | [policies.md](policies.md) | 문서·TODO·코드·측정 규칙 |
| 규칙 | [ai-workflow.md](ai-workflow.md) | 작업 AI / 검수 AI 분리 운영 |
| 명세 | [specs/assist-spec.md](specs/assist-spec.md) | 상품 등록 AI 보조 요청 명세 (SPEC-1) |
| 구조 | [guides/project-structure.md](guides/project-structure.md) | 패키지 경계, 의존 규칙, DTO 패턴 |
| 구조 | [guides/testing-strategy.md](guides/testing-strategy.md) | 테스트 분류, 도구 결정 |
| 구조 | [guides/docker.md](guides/docker.md) | compose / Dockerfile, ECR |
| 배포 | [deploy/aws-setup.md](deploy/aws-setup.md) | AWS 배포 절차 |

<br>

## ADR — 왜 그렇게 선택했나

| # | 주제 | 상태 |
|---|---|---|
| 1 | [모듈러 모놀리스](adr/001-modular-monolith.md) | Accepted |
| 2 | [낙관적 락 + Spring Retry](adr/002-optimistic-locking.md) | Accepted |
| 3 | [Redis Pub/Sub 멀티 인스턴스 채팅](adr/003-redis-pubsub-chat.md) | Accepted |
| 4 | [커서 페이징](adr/004-cursor-pagination.md) | Accepted |
| 5 | [Saga Orchestration (부분 구현)](adr/005-saga-orchestration.md) | Accepted |
| 6 | [AWS 배포 스택](adr/006-aws-stack-choice.md) | Accepted |
| 7 | 상품 등록 AI 보조 | 예정 — [todo.md](todo.md) SPEC-2 |
| 8 | Outbox / PG 연동 | 검토 — [todo.md](todo.md) OUTBOX-1 |
