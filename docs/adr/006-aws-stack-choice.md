# ADR-6 — AWS 배포 스택 선택 (EC2 + ALB + RDS + ElastiCache)

- **Status**: Accepted (2026-05-09)
- **Context**: Phase 3 — 실 배포로 ADR-3 (멀티 인스턴스 채팅) 시연 환경 확보
- **Related**: ADR-1 (모놀리스, 2 인스턴스로 HA), ADR-3 (Redis Pub/Sub cross-instance)

---

## 결정

`us-east-1` 리전에 **EC2 2대 + ALB + RDS MySQL + ElastiCache Redis** 구성을 **수동 AWS CLI 명령**으로 배포.

- 컴퓨트: EC2 t3.micro × 2 (서로 다른 AZ — us-east-1a, us-east-1b)
- 로드밸런서: Application Load Balancer (L7, /actuator/health 헬스체크)
- DB: RDS MySQL 8.0 (db.t3.micro, Single-AZ)
- 캐시: ElastiCache Redis 7.1 (cache.t3.micro, single-node)
- 컨테이너: ECR + Docker (멀티스테이지 빌드)
- IaC: **없음** — 수동 CLI (절차는 [aws-setup.md](../deploy/aws-setup.md))

---

## Context — 무엇을 시연하려 했나

본 프로젝트의 AWS 배포는 **운영 흉내가 아니라 ADR 검증 환경**:
- ADR-3 (Redis Pub/Sub 멀티 인스턴스 채팅) — 정말 2개 JVM 이 떠야 cross-instance 테스트가 실 환경에서 의미 있음
- ADR-2 (낙관적 락) — 실 RDS 의 InnoDB 동시성 동작 확인
- AWS 매니지드 인프라 운영 학습 — 면접에서 "AWS 써봤어요" 가 아니라 "ALB 헬스체크 / SG 분리 / 멀티 AZ 설계 트레이드오프 답변 가능"

요구사항:
- ✅ 2개 이상 EC2 인스턴스
- ✅ L7 로드밸런서 (path / health check)
- ✅ 매니지드 DB (RDS) + 매니지드 Redis (ElastiCache)
- ✅ 비용 통제 — 평소 정지 가능 + 시연 1일 < $2

---

## 옵션 비교 — 컴퓨트 + 런타임

| 옵션 | 장점 | 단점 | 본 프로젝트 적합도 |
|---|---|---|---|
| **EC2 + Docker (선택)** | 명시적 / 친숙 / 멀티 인스턴스 직관 / SSM 디버깅 가능 | OS 패치 / 보안 그룹 등 관리 부담 | **○** |
| ECS / Fargate | 컨테이너 추상 / 운영 부담 ↓ | Cold start 비용 / 디버깅이 SSH 안 됨 | △ — 학습 가치는 EC2 가 더 큼 |
| EKS (관리형 K8s) | 가장 운영 표준 | **EKS 컨트롤 플레인 $73/월** + 워커 EC2 별도 | ✗ — 비용 폭증 |
| Lightsail | 가장 저렴 ($5/월~) | ALB / RDS / ElastiCache 통합 약함 | ✗ — 매니지드 인프라 시연 못 함 |
| PaaS (Heroku / Render / Fly) | 배포 가장 쉬움 | 매니지드 인프라 = 블랙박스 → 면접 어필 안 됨 | ✗ |

**EC2 선택 이유**:
- 멀티 인스턴스 환경이 **눈에 보임** — `ssh ec2-1`, `ssh ec2-2` 가 시연 그 자체
- SSM 으로 컨테이너 로그 직접 봄 (Spring Boot 디버깅 시 핵심)
- 인프라 결정 (SG / IAM / user-data) 을 명시적으로 학습 / 면접 답변

**EKS 기각 이유**:
- 컨트롤 플레인 $73/월 (~$2.4/일) → 본 프로젝트의 일일 가동 비용 ~$1.6 보다 컨트롤 플레인 단독이 더 비쌈
- K8s 매니페스트는 작성만 해둠 (`k8s/` 디렉토리, 추가 예정) → "K8s 알아요" 어필은 매니페스트 코드로 충분

---

## 옵션 비교 — 리전

| 리전 | EC2 t3.micro / 시 | RDS db.t3.micro / 시 | 비고 |
|---|---|---|---|
| **us-east-1 (선택)** | $0.0104 | $0.017 | 가장 저렴 + 가장 새 기능 빠름 |
| us-west-2 | 같음 | 같음 | 같음 |
| ap-northeast-2 (서울) | $0.0130 (+25%) | $0.025 (+47%) | latency 유리 — 한국 시연 시 |

**us-east-1 선택 이유**:
- 데모 시연 latency 차이 (~150ms vs ~10ms) 가 면접 시연 가치 대비 무시 가능
- 비용 ~25-47% 절감 — 단독 개발자 ROI 우선
- 새 AWS 기능 가장 빨리 — RDS 8.x / ElastiCache 7.1 등

---

## 옵션 비교 — IaC

| 옵션 | 학습 곡선 | 일관성 | 본 프로젝트 적합도 |
|---|---|---|---|
| **수동 CLI (선택)** | 가장 낮음 | 명령 누적 = 문서 | **○ — 학습 + 시연 빠름** |
| Terraform | 중간 | 코드 = 인프라 | △ — 시간 투자 필요 |
| AWS CDK | 중간 (TS/Python) | 같음 | △ |
| CloudFormation | 높음 (YAML 학습) | 같음 | ✗ — 학습 비용 대비 ROI 낮음 |
| Ansible / Chef | 낮음 (인프라엔 부적합) | — | ✗ |

**수동 CLI 선택 이유**:
- 본 프로젝트 일정 (3주) 안에서 Terraform 학습 + 모듈 작성 = 2-3일 — ADR / 통합 테스트 우선
- 수동 CLI 의 모든 명령을 [`aws-setup.md`](../deploy/aws-setup.md) 에 누적 기록 → 그 자체가 문서 + 재현 가능
- AWS 콘솔 클릭으로 안 하고 CLI 박은 거 자체가 "스크립트화 가능" 증명

**Terraform 으로 이전할 시점**:
- 환경이 dev / staging / prod 로 늘어남
- 인프라 변경이 매일 일어남
- 팀 협업 필요 (현재 단독)

---

## 보안 그룹 (SG) 설계 — 최소 권한

```
Internet ─ 80 ────────► [sg-alb]
                            │
                       │ 8080 (from sg-alb)
                            ▼
                        [sg-ec2] ───────┬───── 3306 (to sg-rds)
                            │           │
                       │ 22 (from MY_IP)│
                            │           ▼
                            │       [sg-rds]
                            │
                            └──── 6379 (to sg-redis) ──► [sg-redis]
```

원칙:
- **공개**: ALB 80 만 — `0.0.0.0/0`
- **인스턴스 간**: SG ID 참조 (CIDR 아님) — IP 변경에도 안전
- **SSH**: 개발자 본인 IP/32 만 (디버깅 용 — SSM 이 우선이지만 fallback)
- **DB / Redis**: 인터넷 차단 + EC2 SG 만 허용 — endpoint 노출되어도 외부 접근 0

→ ADR-1 의 "RDS endpoint 노출 위험도 LOW" 의 근거가 여기.

---

## 비용 시뮬레이션 (us-east-1, 24h 풀가동)

| 자원 | 시간 단가 | 24h | 30일 |
|---|---|---|---|
| EC2 t3.micro × 2 | $0.0104 × 2 | $0.50 | $15.00 |
| RDS db.t3.micro | $0.017 | $0.41 | $12.30 |
| ElastiCache cache.t3.micro | $0.017 | $0.41 | $12.30 |
| ALB | $0.0225 + LCU | ~$0.60 | ~$18.00 |
| RDS 스토리지 (20GB gp3) | — | $0.07 | $2.30 |
| **합계** | | **~$2.00** | **~$60** |

→ 면접 직전 ~15분 재배포 후 1-2일 가동 = **$2-4**. 충분히 감당.

**비용 통제 전략**:
- EC2 / RDS — `stop` 가능 (인스턴스 시간 0, 스토리지만 비용)
- ALB / ElastiCache — `stop` 불가능, 매번 **delete → 재생성**
- ECR / IAM / SG / VPC — 모두 무료, 영구 유지
- 시연 끝나면 [`cleanup.sh`](../deploy/cleanup.sh) 실행

---

## Consequences

### 얻은 것
- **ADR-3 cross-instance 채팅을 실 환경에서 시연 가능**
- AWS 매니지드 인프라 운영 경험 — 면접 답변: "ALB 헬스체크 / SG 최소 권한 / IAM role / SSM 으로 디버깅"
- 비용 통제 — 시연 1일 < $2 + 평소 정지 시 거의 0
- 모든 명령이 문서화 → 재현 가능 (옛 ALB DNS / RDS endpoint 등은 매번 새로 발급)

### 대가
- **IaC 없음** — Terraform 으로 이전 못 함 (인프라 변경 추적 / 코드 리뷰 어려움)
- **Single-AZ** — RDS / Redis 의 가용성 트레이드오프. 운영 시 Multi-AZ 로 전환만 하면 됨 (RDS 는 클릭 한 번)
- **수동 재배포 ~15분** — 본격 운영이면 GitHub Actions + Terraform / CDK 로 자동화 필요
- **모니터링 / 알람** — CloudWatch 기본만. Prometheus / Grafana 미적용 (다음 단계)

### 언제 재검토하나
- 시연이 잦아지거나 환경이 dev / staging / prod 로 늘어남 → Terraform 도입
- 가용성 SLO 가 99%+ 필요 → RDS Multi-AZ, ElastiCache cluster mode, EC2 ASG
- 트래픽이 ~100 RPS 이상 → ALB 비용 ↑ + EC2 인스턴스 타입 ↑ + RDS Read Replica
- 컨테이너 개수가 ~10+ → ECS Fargate 또는 EKS 검토 (이 시점이면 EKS 비용도 정당화)

---

## 면접 대비

### Q. "왜 EKS 안 했어요? K8s 가 표준인데"

**A**: "EKS 컨트롤 플레인이 월 $73 인데 본 프로젝트 일일 가동 비용 ~$1.6 보다 비쌉니다. 즉 시연용 인프라로는 ROI 가 안 맞아요. 본 프로젝트 가치는 K8s 가 아니라 ADR (동시성 / 분산 / 채팅) 입증이고, EC2 + Docker 로도 같은 가치를 입증할 수 있습니다. **K8s 매니페스트는 작성만 해두기** — 다음 단계로 EKS 배포 가능한 구조 (`k8s/` 디렉토리에 deployment / service / ingress / HPA) 만 있음. 비용 정당화되면 그대로 `kubectl apply` 가능."

### Q. "Terraform 으로 안 한 이유는?"

**A**: "3주 단독 개발 일정 안에서 Terraform 학습 + 모듈 작성에 2-3일 투자가 필요했고, 그 시간을 ADR 작성 / 통합 테스트 / 부하 시연에 우선 배분했습니다. **수동 CLI 명령을 [`aws-setup.md`](../deploy/aws-setup.md) 에 누적 기록** — 그 자체가 재현 가능한 문서고, 다음 누군가가 같은 환경을 만들 수 있어요. 환경이 dev / staging / prod 로 늘어나거나 인프라 변경이 매일 일어나면 Terraform 으로 이전 — 그땐 본 문서가 module 작성의 reference 가 됩니다."

### Q. "Single-AZ 인데 가용성은 어떻게 보장하나요?"

**A**: "본 시연은 **운영 가용성 시연이 아닌 ADR 시연 환경**입니다. EC2 만 2대 cross-AZ 로 두고 (chat 인스턴스 분산이 ADR-3 의 핵심), RDS / ElastiCache 는 Single-AZ 로 비용 절감. **운영 전환 시점에 둘 다 Multi-AZ 토글 1회** — RDS 는 콘솔에서 클릭, ElastiCache 는 cluster mode 활성화. 본 프로젝트의 가치 (ADR 입증) 는 Single-AZ 로도 충분히 보임. 가용성을 본격 측정하려면 chaos engineering (AZ 강제 다운) 까지 가야 하는데 그건 다음 단계."

### Q. "SG 설정에서 가장 신경 쓴 부분?"

**A**: "**SG 간 참조 (CIDR 아님)**. EC2 ↔ RDS / Redis 사이 트래픽은 EC2 의 IP 가 변해도 SG ID 로 연결되니 안 끊깁니다. 또 인터넷 노출은 ALB 80 만 — RDS / Redis 의 endpoint 가 GitHub 노출되더라도 (실제 본 프로젝트 옛 commit 에 있음) 외부에서 직접 접속 불가. **defense in depth** 의 첫 layer."

---

## References

- AWS Pricing Calculator (us-east-1): https://calculator.aws
- 본 프로젝트 배포 절차: [`docs/deploy/aws-setup.md`](../deploy/aws-setup.md)
- 정리 스크립트: [`docs/deploy/cleanup.sh`](../deploy/cleanup.sh)
