# AWS 배포 가이드

> Docker 이미지가 빌드된 상태에서, AWS CLI 명령으로 
> EC2 + ALB + RDS + ElastiCache 환경을 재현할 수 있도록 정리한 절차.

## 스펙 결정 이유

| 항목 | 선택 | 이유 |
|---|---|---|
| 리전 | us-east-1 | 다른 리전 대비 15~24% 저렴 |
| 컴퓨트 | EC2 (Docker) | EC2 를 직접 조작하며 학습 |
| DB / Redis | RDS + ElastiCache | 백업·패치·모니터링을 AWS 가 처리하여 운영 부담 최소화 |
| VPC | Default VPC | 추가 비용 0 |
| IaC | AWS CLI 수동 스크립트 | 빠르게 적용 가능 |
| 도메인 | ALB 기본 DNS | 추가 비용 0 |

## 환경 변수

터미널을 새로 열 때마다 아래 명령들을 실행해야 합니다. export 로 
설정한 환경 변수는 셸 세션이 끝나면 사라지기 때문입니다.

DB 비밀번호·JWT 키·ARN 같은 민감한 값은 `/tmp/usedtrade-secrets.env` 
파일에 따로 저장하고, 매번 source 로 로드합니다.

```bash
# ─── Git Bash / Windows 환경 (Windows 사용자만) ───
export PATH="/c/Program Files/Amazon/AWSCLIV2:$PATH"
export MSYS_NO_PATHCONV=1

# ─── AWS 기본 설정 ───
export AWS_REGION=us-east-1
export AWS_ACCOUNT_ID=<YOUR-AWS-ACCOUNT-ID>

# ─── 프로젝트 식별자 ───
export PROJECT=usedtrade
export VPC_ID=<YOUR-VPC-ID>
export ECR_URI="${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/${PROJECT}"

# ─── 시크릿 로드 (DB_PASSWORD / JWT_SECRET / 각 ARN) ───
source /tmp/usedtrade-secrets.env
```

### `<YOUR-...>` 값 확인 방법

- **AWS_ACCOUNT_ID**: `aws sts get-caller-identity --query Account --output text`
- **VPC_ID**: `aws ec2 describe-vpcs --filters Name=is-default,Values=true --query 'Vpcs[0].VpcId' --output text`

## 1. ECR 저장소 생성 및 이미지 업로드

로컬 PC의 Docker 이미지를 AWS ECR에 올립니다.

```bash
aws ecr create-repository \
  --repository-name "$PROJECT" \
  --region "$AWS_REGION" \
  --image-scanning-configuration scanOnPush=true \
  --encryption-configuration encryptionType=AES256
```

```bash
aws ecr get-login-password --region "$AWS_REGION" \
  | docker login --username AWS --password-stdin \
      "${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"

docker tag usedtrade-app:local "${ECR_URI}:latest"
docker tag usedtrade-app:local "${ECR_URI}:v1"
docker push "${ECR_URI}:v1"
docker push "${ECR_URI}:latest"
```

## 2. AWS Budgets 알람 설정

AWS 비용이 예상보다 늘어나면 이메일로 알림을 받도록 월 예산 임계값을 $10 으로 설정합니다.

`/tmp/budget.json` 과 `/tmp/notifications.json` 에 JSON 작성 (본문은 commit 참고).

<details> 
<summary>budget.json 예시</summary>

```json
{
  "BudgetName": "usedtrade-monthly-10usd",
  "BudgetLimit": { "Amount": "10", "Unit": "USD" },
  "TimeUnit": "MONTHLY",
  "BudgetType": "COST"
}
```
</details>

<details>
<summary>notifications.json 예시</summary>

`you@example.com` 부분은 실제 알림 받을 이메일로 치환 후 실행

```json
[ 
  {
    "Notification": {
      "NotificationType": "ACTUAL",
      "ComparisonOperator": "GREATER_THAN",
      "Threshold": 80,
      "ThresholdType": "PERCENTAGE"
    },
    "Subscribers": [
      { "SubscriptionType": "EMAIL", "Address": "you@example.com" }
    ]
  },
  {
    "Notification": {
      "NotificationType": "FORECASTED",
      "ComparisonOperator": "GREATER_THAN",
      "Threshold": 100,
      "ThresholdType": "PERCENTAGE"
    },
    "Subscribers": [
      { "SubscriptionType": "EMAIL", "Address": "you@example.com" }
    ]
  }
]
```

</details>
<br>

```bash
aws budgets create-budget \
  --account-id "$AWS_ACCOUNT_ID" \
  --budget "file://$(cygpath -m /tmp/budget.json)" \
  --notifications-with-subscribers "file://$(cygpath -m /tmp/notifications.json)"
```

## 3. Security Groups
리소스 사이 트래픽이 필요한 경로만 허용되도록 4개의 방화벽 그룹을 설정합니다.

```bash
# sg-alb : 80/443 from anywhere
aws ec2 create-security-group --group-name usedtrade-alb \
  --description "ALB - inbound 80/443" --vpc-id "$VPC_ID"
aws ec2 authorize-security-group-ingress --group-id <SG_ALB> \
  --protocol tcp --port 80  --cidr 0.0.0.0/0
aws ec2 authorize-security-group-ingress --group-id <SG_ALB> \
  --protocol tcp --port 443 --cidr 0.0.0.0/0

# sg-ec2 : 8080 from sg-alb, 22 from MY_IP
aws ec2 create-security-group --group-name usedtrade-ec2 \
  --description "EC2 app - 8080 from ALB, SSH" --vpc-id "$VPC_ID"
aws ec2 authorize-security-group-ingress --group-id <SG_EC2> \
  --protocol tcp --port 8080 --source-group <SG_ALB>
aws ec2 authorize-security-group-ingress --group-id <SG_EC2> \
  --protocol tcp --port 22 --cidr "${MY_IP}/32"

# sg-rds : 3306 from sg-ec2
aws ec2 create-security-group --group-name usedtrade-rds \
  --description "RDS - 3306 from EC2" --vpc-id "$VPC_ID"
aws ec2 authorize-security-group-ingress --group-id <SG_RDS> \
  --protocol tcp --port 3306 --source-group <SG_EC2>

# sg-redis : 6379 from sg-ec2
aws ec2 create-security-group --group-name usedtrade-redis \
  --description "ElastiCache - 6379 from EC2" --vpc-id "$VPC_ID"
aws ec2 authorize-security-group-ingress --group-id <SG_REDIS> \
  --protocol tcp --port 6379 --source-group <SG_EC2>
```

## 4. RDS MySQL
백엔드가 사용할 MySQL 8 데이터베이스 인스턴스를 생성합니다.

```bash
# 서브넷 그룹 (최소 2 AZ)
aws rds create-db-subnet-group \
  --db-subnet-group-name usedtrade-rds-subnets \
  --db-subnet-group-description "default VPC public subnets" \
  --subnet-ids <subnet-1a> <subnet-1b> <subnet-1c>

# 인스턴스 생성 (db.t3.micro 프리티어)
aws rds create-db-instance \
  --db-instance-identifier usedtrade-mysql \
  --db-instance-class db.t3.micro \
  --engine mysql --engine-version 8.0.40 \
  --master-username admin --master-user-password "$DB_PASSWORD" \
  --allocated-storage 20 --storage-type gp3 \
  --db-name usedtrade \
  --vpc-security-group-ids <SG_RDS> \
  --db-subnet-group-name usedtrade-rds-subnets \
  --backup-retention-period 1 \
  --no-multi-az --no-publicly-accessible \
  --no-storage-encrypted --no-deletion-protection
```

## 5. ElastiCache Redis
백엔드가 사용할 Redis 7 인스턴스를 생성합니다.

```bash
aws elasticache create-cache-subnet-group \
  --cache-subnet-group-name usedtrade-redis-subnets \
  --cache-subnet-group-description "default VPC subnets" \
  --subnet-ids <subnet-1a> <subnet-1b> <subnet-1c>

aws elasticache create-cache-cluster \
  --cache-cluster-id usedtrade-redis \
  --engine redis --cache-node-type cache.t3.micro \
  --num-cache-nodes 1 --engine-version 7.1 \
  --cache-subnet-group-name usedtrade-redis-subnets \
  --security-group-ids <SG_REDIS> --port 6379
```

## 6. EC2 IAM Role
EC2 가 ECR 이미지 pull 과 SSM 원격 접속을 할 수 있도록 IAM 역할을 부여합니다.

```bash
# trust policy
cat > /tmp/ec2-trust.json <<'JSON'
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": { "Service": "ec2.amazonaws.com" },
    "Action": "sts:AssumeRole"
  }]
}
JSON

aws iam create-role --role-name usedtrade-ec2-role \
  --assume-role-policy-document "file://$(cygpath -m /tmp/ec2-trust.json)"
aws iam attach-role-policy --role-name usedtrade-ec2-role \
  --policy-arn arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly
aws iam attach-role-policy --role-name usedtrade-ec2-role \
  --policy-arn arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore

aws iam create-instance-profile --instance-profile-name usedtrade-ec2-profile
aws iam add-role-to-instance-profile \
  --instance-profile-name usedtrade-ec2-profile \
  --role-name usedtrade-ec2-role
```

## 7. 로드 밸런서 스택
외부 트래픽을 EC2 로 전달하기 위한 로드 밸런서 스택(ALB · Target Group · Listener)을 구성합니다.

```bash
# ALB
ALB_ARN=$(aws elbv2 create-load-balancer \
  --name usedtrade-alb --type application --scheme internet-facing \
  --ip-address-type ipv4 \
  --subnets <subnet-1a> <subnet-1b> \
  --security-groups <SG_ALB> \
  --query 'LoadBalancers[0].LoadBalancerArn' --output text)

# TG (HTTP 8080, /actuator/health)
TG_ARN=$(aws elbv2 create-target-group \
  --name usedtrade-tg --protocol HTTP --port 8080 \
  --vpc-id "$VPC_ID" --target-type instance \
  --health-check-protocol HTTP --health-check-port 8080 \
  --health-check-path /actuator/health \
  --health-check-interval-seconds 15 \
  --healthy-threshold-count 2 --unhealthy-threshold-count 3 \
  --matcher 'HttpCode=200' \
  --query 'TargetGroups[0].TargetGroupArn' --output text)

# Listener (80 → TG)
aws elbv2 create-listener --load-balancer-arn "$ALB_ARN" \
  --protocol HTTP --port 80 \
  --default-actions Type=forward,TargetGroupArn="$TG_ARN"
```

## 8. EC2 인스턴스 시작 및 등록
`user-data` 로 앱을 자동 실행하는 EC2 인스턴스를 시작하고 Target Group 에 등록합니다.

`user-data` = EC2 가 처음 부팅될 때 한 번 자동 실행되는 스크립트.

`/tmp/user-data.sh` 의 핵심 흐름:
```bash
#!/bin/bash
set -euxo pipefail
dnf update -y && dnf install -y docker
systemctl enable --now docker
aws ecr get-login-password --region us-east-1 \
  | docker login --username AWS --password-stdin <ECR-host>
docker pull <ECR-uri>:latest
docker run -d --name usedtrade --restart unless-stopped \
  -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=docker \
  -e DB_HOST=... -e DB_USER=admin -e DB_PASSWORD=... \
  -e REDIS_HOST=... -e REDIS_PORT=6379 -e REDIS_PASSWORD= \
  -e JWT_SECRET=... \
  <ECR-uri>:latest
```

```bash
aws ec2 run-instances \
  --image-id $(aws ssm get-parameter \
    --name /aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64 \
    --query 'Parameter.Value' --output text) \
  --instance-type t3.micro \
  --iam-instance-profile Name=usedtrade-ec2-profile \
  --security-group-ids <SG_EC2> \
  --subnet-id <subnet-1a> \
  --associate-public-ip-address \
  --user-data "fileb://$(cygpath -m /tmp/user-data.sh)" \
  --tag-specifications 'ResourceType=instance,Tags=[{Key=Name,Value=usedtrade-app-1}]'

# TG 등록
aws elbv2 register-targets --target-group-arn "$TG_ARN" \
  --targets Id="<INSTANCE_ID>",Port=8080
```

> **멀티 인스턴스 시연 ([ADR-3](../adr/003-redis-pubsub-chat.md))**: 
> 두 번째 EC2 인스턴스를 다른 가용영역 (us-east-1b) 에 같은 user-data 로 
> 시작하고 같은 Target Group 에 등록하면, 두 EC2 가 같은 Redis 인스턴스를 
> 공유하게 됩니다. 이 상태에서 한 EC2 로 온 채팅 메시지가 Redis Pub/Sub 
> 를 통해 다른 EC2 의 사용자에게 전달되는 흐름을 시연할 수 있습니다.

## 9. EC2 진단 (SSM)

SSH 키 없이 EC2 안에 접속해 로그와 상태를 확인합니다.

```bash
# 명령 원격 실행 (output 비동기)
CMD_ID=$(aws ssm send-command \
  --instance-ids <INSTANCE_ID> \
  --document-name "AWS-RunShellScript" \
  --parameters 'commands=["docker ps -a","docker logs usedtrade 2>&1 | tail -50"]' \
  --query 'Command.CommandId' --output text)
sleep 5
aws ssm get-command-invocation \
  --command-id "$CMD_ID" --instance-id <INSTANCE_ID> \
  --query 'StandardOutputContent' --output text

# 인터랙티브 셸 (Session Manager Plugin 필요)
aws ssm start-session --target <INSTANCE_ID>
```

## 10. 리소스 정리 (cleanup.sh)

데모가 끝나면 유료 리소스를 정리해 요금을 0 으로 만듭니다.

정리 스크립트: [`docs/deploy/cleanup.sh`](cleanup.sh)

## 비용 요약 (us-east-1, 2026-08 기준)

> AWS 가격은 변동될 수 있음

| 리소스 | 시간당 | 24h | 한 달 |
|---|---|---|---|
| ALB | $0.0225 | $0.54 | ~$16 |
| ElastiCache cache.t3.micro | $0.017 | $0.41 | ~$13 |
| EC2 t3.micro × 2 (#1 프리티어) | $0.0104 | $0.25 | ~$8 |
| RDS db.t3.micro (프리티어) | $0 | $0 | $0 |
| EBS 20GB (프리티어 30GB) | $0 | $0 | $0 |
| ECR storage (~180MB, 무료 500MB) | $0 | $0 | $0 |
| **데모 패턴 (5h/월)** | — | — | **~$0.5** |
| **24h 풀가동 시** | **~$0.05** | **~$1.2** | **~$37** |

데모 끝나면 **즉시 [cleanup.sh](cleanup.sh)** 실행 권장.
