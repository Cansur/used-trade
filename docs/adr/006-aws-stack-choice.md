# ADR-6 - AWS 배포 스택


### 상태
- **Status**: Accepted (2026-05-09)

## 배경

Redis Pub/Sub을 확인하기 위해서는 멀티 인스턴스 AWS 배포가 필요합니다.<br>
추가로 AWS 인프라 경험을 하기 위해서 진행했습니다.

- 로드 밸런서 + 다중 인스턴스
- 매니지드 DB + 매니지드 Redis
- AWS 매니지드 인프라 경험 확보

## 결론

`EC2 2대 + ALB + RDS MySQL + ElastiCache Redis`

- 컨테이너 : ECR + Docker 멀티스테이지 빌드
- IaC 없음 : 수동 AWS CLI

## 비교

### 실행 환경

|  | EC2 + Docker | EKS | PaaS |
|---|---|---|---|
| 비용 | ~$60/월 | $73/월 ~ (컨트롤 플레인만) + 워커 별도 | $20~$50/월 |
| 매니지드 인프라 통합 | 명시적 (콘솔 확인) | K8s 추상 | 블랙박스 |
| 학습 가치 | SG·IAM·user-data | K8s 표준 | 없음 |

**EKS 선택 X** — 컨트롤 플레인 단독 비용이 본 프로젝트 일일 가동 비용보다 비쌈

**PaaS 선택 X** — 인프라가 블랙박스

### IaC

|  | 수동 CLI | Terraform |
|---|---|---|
| 학습 비용 | 0 | 2~3일 |
| 재현성 | 명령을 문서에 누적 | 코드로 자동 |

**Terraform 선택 X** - 3주 일정에서 학습 2~3일을 ADR·테스트·시연에 우선 배분. 대신 CLI 명령을 `docs/deploy/aws-setup.md` 에 누적 기록해 재현 가능 문서로 대체.

<br>

## 구현 방법

### 1. 인스턴스 명세

| 자원 | 사양 | 비고 |
|---|---|---|
| EC2 × 2 | t3.micro |  |
| ALB | L7, `/actuator/health` 헬스체크 | 15초 간격, 3회 실패 시 unhealthy |
| RDS MySQL 8.0 | db.t3.micro(Single-AZ) | 운영 시 Multi-AZ 토글 |
| ElastiCache Redis 7.1 | cache.t3.micro(single-node) | AUTH 비활성(SG로 대체) |

### 2. 보안 그룹 — 최소 권한

| SG | 허용 인바운드 | 의미 |
|---|---|---|
| sg-alb | `0.0.0.0/0:80` | 인터넷에서 ALB 만 |
| sg-ec2 | `sg-alb:8080` | ALB 에서만 EC2 |
| sg-rds | `sg-ec2:3306` | EC2 에서만 RDS |
| sg-redis | `sg-ec2:6379` | EC2 에서만 Redis |

### 3. 배포 흐름

Docker 멀티스테이지 → ECR push → EC2 user-data 가 ECR에서 pull + 컨테이너 실행.

<br>

## 에러 상황

### trade-off

| 영역 | 미적용 | 운영 시 보강 |
|---|---|---|
| IaC | Terraform 미도입 (수동 CLI) | 추후 Terraform 도입 |
| 가용성 | RDS·ElastiCache(Single-AZ) | 콘솔에서 Multi-AZ 토글 |
| 모니터링 | CloudWatch 기본만, Prometheus·Grafana 미적용 | Actuator + Micrometer → Prometheus → Grafana |
| 자동 배포 | GitHub Actions 미연결 | GHA 트리거 - ECR 빌드·푸시·EC2 재시작 자동화 |

## 참조

- [Dockerfile](../../Dockerfile) — 멀티스테이지 빌드
- [docker-compose.yml](../../docker-compose.yml) — 로컬 통합 환경
- [docs/deploy/aws-setup.md](../deploy/aws-setup.md) — AWS 배포 절차
- [docs/guides/docker.md](../guides/docker.md) — Dockerfile / docker-compose