#!/usr/bin/env bash
# ===================================================================
# AWS 데모 정리 — 시간당 비용 발생 리소스 모두 삭제 / stop.
# 무료 / 거의 무료인 ECR / IAM / Budget / SG 는 유지 (재배포 시 재사용).
#
# 실행: bash docs/deploy/cleanup.sh [stop|delete]
#   stop   — EC2 + RDS 만 stop (재시작 가능). ALB / ElastiCache 는 삭제 (stop 불가).
#   delete — 위 전부 + RDS / EC2 / Subnet group 까지 삭제 (완전 초기화).
# 기본: stop
# ===================================================================

set -euo pipefail

MODE="${1:-stop}"

export PATH="/c/Program Files/Amazon/AWSCLIV2:$PATH"
export MSYS_NO_PATHCONV=1

REGION="${AWS_REGION:-us-east-1}"

echo "[cleanup] mode = $MODE  region = $REGION"
echo ""

# ----- 1) ALB Listener / TG / ALB 삭제 (stop 불가, 항상 delete) -----
echo "--- ALB ---"
ALB_ARN=$(aws elbv2 describe-load-balancers --region "$REGION" \
  --names usedtrade-alb --query 'LoadBalancers[0].LoadBalancerArn' --output text 2>/dev/null || echo "")
if [ -n "$ALB_ARN" ] && [ "$ALB_ARN" != "None" ]; then
  # Listener 먼저
  for L in $(aws elbv2 describe-listeners --load-balancer-arn "$ALB_ARN" --region "$REGION" \
              --query 'Listeners[*].ListenerArn' --output text); do
    aws elbv2 delete-listener --listener-arn "$L" --region "$REGION"
    echo "  deleted listener $L"
  done
  aws elbv2 delete-load-balancer --load-balancer-arn "$ALB_ARN" --region "$REGION"
  echo "  deleted ALB $ALB_ARN"
else
  echo "  (no usedtrade-alb)"
fi

echo "--- Target Group ---"
TG_ARN=$(aws elbv2 describe-target-groups --region "$REGION" \
  --names usedtrade-tg --query 'TargetGroups[0].TargetGroupArn' --output text 2>/dev/null || echo "")
if [ -n "$TG_ARN" ] && [ "$TG_ARN" != "None" ]; then
  # ALB 삭제 후 TG 삭제 가능 (잠시 대기)
  sleep 5
  aws elbv2 delete-target-group --target-group-arn "$TG_ARN" --region "$REGION" || true
  echo "  deleted TG $TG_ARN"
else
  echo "  (no usedtrade-tg)"
fi

# ----- 2) ElastiCache 삭제 (stop 불가) -----
echo "--- ElastiCache ---"
if aws elasticache describe-cache-clusters --cache-cluster-id usedtrade-redis --region "$REGION" >/dev/null 2>&1; then
  aws elasticache delete-cache-cluster --cache-cluster-id usedtrade-redis --region "$REGION"
  echo "  deleting usedtrade-redis (~5분)"
  if [ "$MODE" = "delete" ]; then
    aws elasticache wait cache-cluster-deleted --cache-cluster-id usedtrade-redis --region "$REGION"
    aws elasticache delete-cache-subnet-group --cache-subnet-group-name usedtrade-redis-subnets --region "$REGION" || true
    echo "  deleted Redis subnet group"
  fi
else
  echo "  (no usedtrade-redis)"
fi

# ----- 3) EC2 (stop 또는 terminate) -----
echo "--- EC2 ---"
INSTANCE_IDS=$(aws ec2 describe-instances --region "$REGION" \
  --filters "Name=tag:Name,Values=usedtrade-app-*" "Name=instance-state-name,Values=running,stopped,pending" \
  --query 'Reservations[*].Instances[*].InstanceId' --output text)
if [ -n "$INSTANCE_IDS" ]; then
  if [ "$MODE" = "delete" ]; then
    aws ec2 terminate-instances --instance-ids $INSTANCE_IDS --region "$REGION" >/dev/null
    echo "  terminating: $INSTANCE_IDS"
  else
    aws ec2 stop-instances --instance-ids $INSTANCE_IDS --region "$REGION" >/dev/null
    echo "  stopping: $INSTANCE_IDS  (재시작은 aws ec2 start-instances ...)"
  fi
else
  echo "  (no usedtrade-app-* instances)"
fi

# ----- 4) RDS (stop 또는 delete) -----
echo "--- RDS ---"
if aws rds describe-db-instances --db-instance-identifier usedtrade-mysql --region "$REGION" >/dev/null 2>&1; then
  if [ "$MODE" = "delete" ]; then
    aws rds delete-db-instance \
      --db-instance-identifier usedtrade-mysql \
      --skip-final-snapshot --delete-automated-backups \
      --region "$REGION" >/dev/null
    echo "  deleting usedtrade-mysql (~5-10분)"
    aws rds wait db-instance-deleted --db-instance-identifier usedtrade-mysql --region "$REGION"
    aws rds delete-db-subnet-group --db-subnet-group-name usedtrade-rds-subnets --region "$REGION" || true
    echo "  deleted RDS subnet group"
  else
    aws rds stop-db-instance --db-instance-identifier usedtrade-mysql --region "$REGION" >/dev/null
    echo "  stopping usedtrade-mysql  (max 7일 후 자동 시작)"
  fi
else
  echo "  (no usedtrade-mysql)"
fi

echo ""
echo "[done] mode=$MODE"
echo ""
echo "유지된 리소스 (무료/거의 무료):"
echo "  - ECR repo (이미지 ~180MB, 무료 영역)"
echo "  - IAM Role / Instance Profile"
echo "  - Security Groups 4종"
echo "  - AWS Budget 알람"
echo ""
echo "재배포: docs/deploy/aws-setup.md 의 Step 8 (EC2 launch) 부터."
