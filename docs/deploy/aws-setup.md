# AWS 배포 가이드 — Stage 2

> Stage 1 (Dockerization) 이후 실제 AWS 환경에 배포한 셋업 명령 누적.
> 데모 시연용. 운영 패턴은 Terraform 또는 ECS/Fargate 권장 — ADR 한 줄로 메모.

## 결정 요약

| 항목 | 선택 | 이유 |
|---|---|---|
| 리전 | `us-east-1` | RDS / EC2 / ElastiCache ~15-24% 저렴, 프리티어 동일, latency 차이는 데모에 무관 |
| 호스팅 | EC2 + Docker | 친숙, 명시적, 다중 인스턴스 시연 명확 |
| DB / Redis | RDS + ElastiCache (풀 매니지드) | 운영 일관성 + 면접 어필. 비용은 데모 시에만 켜고 끔 |
| VPC | Default VPC + public subnets | NAT Gateway $32/월 회피. SG 로 격리 |
| IaC | 수동 CLI (이 문서) | 단순, 학습 곡선 낮음. Terraform 은 다음 단계 |
| 도메인 | ALB DNS 그대로 | 데모용. 운영은 Route 53 |

## 환경 변수

매 셸 세션 시작 시 source. 비밀들은 `/tmp/usedtrade-secrets.env` 에 별도 저장 (git ignore).

```bash
export PATH="/c/Program Files/Amazon/AWSCLIV2:$PATH"   # Git Bash 의 PATH 수정 필요할 때
export MSYS_NO_PATHCONV=1                              # /actuator/health 같은 path 자동 변환 비활성화

export AWS_REGION=us-east-1
export AWS_ACCOUNT_ID=<YOUR-AWS-ACCOUNT-ID>      # `aws sts get-caller-identity --query Account --output text` 로 확인
export PROJECT=usedtrade
export ECR_URI="${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/${PROJECT}"
export VPC_ID=<YOUR-VPC-ID>                       # `aws ec2 describe-vpcs --filters Name=is-default,Values=true --query 'Vpcs[0].VpcId' --output text`

source /tmp/usedtrade-secrets.env   # DB_PASSWORD / REDIS_PASSWORD / JWT_SECRET / 각 ARN
```

## 1. ECR 레포지토리

```bash
aws ecr create-repository \
  --repository-name "$PROJECT" \
  --region "$AWS_REGION" \
  --image-scanning-configuration scanOnPush=true \
  --encryption-configuration encryptionType=AES256
```

이미지 push:
```bash
aws ecr get-login-password --region "$AWS_REGION" \
  | docker login --username AWS --password-stdin \
      "${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"

docker tag usedtrade-app:local "${ECR_URI}:latest"
docker tag usedtrade-app:local "${ECR_URI}:v1"
docker push "${ECR_URI}:v1"
docker push "${ECR_URI}:latest"
```

## 2. AWS Budgets — $10/월 알람

`/tmp/budget.json` 과 `/tmp/notifications.json` 에 JSON 작성 (본문은 commit 참고).

```bash
aws budgets create-budget \
  --account-id "$AWS_ACCOUNT_ID" \
  --budget "file://$(cygpath -m /tmp/budget.json)" \
  --notifications-with-subscribers "file://$(cygpath -m /tmp/notifications.json)"
```

## 3. Security Groups (4 종)

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

## 7. ALB + Target Group + Listener

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

## 8. EC2 launch (user-data 로 docker pull + run)

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

ADR-3 의 cross-instance Redis Pub/Sub 시연 — 같은 user-data 로 EC2 #2 를 다른 AZ (us-east-1b) 에 launch + TG 에 등록하면 두 인스턴스가 같은 Redis 를 공유.

## 9. 진단 — SSM Session

SSH 키 없이 EC2 안 진입:
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

## 10. 데모 정리 — `cleanup.sh`

데모 끝나면 다음 스크립트 실행해 비용 0 으로. ECR + IAM + Budget 은 무료라 그대로 유지 가능.

`docs/deploy/cleanup.sh` 참고.

## 비용 요약 (us-east-1, 24h 풀가동 기준)

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

데모 끝나면 **즉시 cleanup.sh** 실행 권장.
