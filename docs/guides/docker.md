# Docker 가이드

> 로컬 개발의 `docker-compose.yml` + 운영용 `Dockerfile` 의 결정 / 작동 방식. 면접 답변: "Docker 어떻게 구성하셨어요?"

---

## 파일 한 줄 요약

| 파일 | 역할 |
|---|---|
| `docker-compose.yml` | 로컬: MySQL + Redis 인프라 (항상), 앱 컨테이너 (선택적, `--profile app`) |
| `Dockerfile` | 운영: 멀티스테이지 빌드 — Stage 1 Gradle 빌드, Stage 2 slim 런타임 |
| `.env.example` | 환경변수 템플릿 — 사용자가 `.env` 로 복사 후 값 채움 |

---

## docker-compose.yml — 로컬 통합 환경

### 구성

```yaml
services:
  mysql:    # 항상 실행 (개발 시 IDE / 테스트가 의존)
  redis:    # 항상 실행
  app:      # profile: "app" — 명시적으로 켤 때만 (예: docker compose --profile app up)
```

### 결정 — MySQL

**호스트 포트 3307 → 컨테이너 3306**:
```yaml
ports:
  - "3307:3306"
```
- 이유: 호스트에 다른 MySQL 깔려있어 3306 점유 가능 → 충돌 회피
- 안에서 (IDE / 테스트 가 호스트로 접속) 는 `localhost:3307` 사용
- 컨테이너끼리 (compose 네트워크 안) 는 `mysql:3306` (호스트명 = 서비스명)

**UTF-8 강제**:
```yaml
command:
  - --character-set-server=utf8mb4
  - --collation-server=utf8mb4_unicode_ci
  - --skip-character-set-client-handshake
```
- 이유: 한글 / 이모지 안전. `utf8` (3바이트) 가 아닌 `utf8mb4` (4바이트) — 이모지 지원

**타임존 KST**:
```yaml
command:
  - --default-time-zone=+09:00
environment:
  TZ: Asia/Seoul
```
- 이유: 한국 사용자 대상 서비스 — DB 의 `NOW()` 가 KST. 운영에서 UTC 로 가야 한다면 변경.

**Healthcheck**:
```yaml
healthcheck:
  test: ["CMD", "mysqladmin", "ping", "-h", "localhost", "-u", "root", "-p${MYSQL_ROOT_PASSWORD}"]
  interval: 10s
  timeout: 5s
  retries: 5
  start_period: 30s
```
- 이유: `app` 서비스의 `depends_on.condition: service_healthy` 와 연결 — MySQL 가 준비된 후 앱이 시작

**볼륨 영속화**:
```yaml
volumes:
  - mysql_data:/var/lib/mysql
```
- 이유: 컨테이너 재시작해도 데이터 유지. `docker compose down -v` 로만 데이터 삭제

### 결정 — Redis

**`requirepass` 적용**:
```yaml
command:
  - redis-server
  - --requirepass
  - ${REDIS_PASSWORD}
```
- 이유: 로컬 개발에도 비번 적용 — 위생 (production 과 같은 방식)
- **단, AWS ElastiCache 는 AUTH 비활성화** — AWS 의 SG (network 분리) 가 인증 대신함. Docker compose 의 `REDIS_PASSWORD` 와 환경 다름. application-docker.yaml 의 `${REDIS_PASSWORD}` 가 환경별로 채워짐

**`maxmemory` + LRU**:
```yaml
command:
  - --maxmemory
  - 256mb
  - --maxmemory-policy
  - allkeys-lru
```
- 이유: 로컬 메모리 보호. LRU 로 가장 안 쓰는 키부터 제거
- 운영 (ElastiCache) 은 인스턴스 타입의 메모리 그대로 사용 — `cache.t3.micro` = 0.5 GiB

**AOF (append-only file) 영속화**:
```yaml
command:
  - --appendonly
  - "yes"
```
- 이유: Redis 다운 시 데이터 손실 최소화. RDB snapshot 만 쓰면 마지막 snapshot 이후 데이터 손실
- 본 프로젝트의 Redis 용도: refresh token (분실 가능 — 사용자 재로그인), JWT 블랙리스트 (분실 시 약간의 보안 위협), 채팅 Pub/Sub (휘발성 OK — MySQL 에 영속화됨)
- AOF 로 추가 비용 (디스크 I/O) 작음 — 개발 환경이라 OK

### 결정 — app 서비스 (profile)

```yaml
app:
  profiles: ["app"]
  build: { context: ., dockerfile: Dockerfile }
  depends_on:
    mysql: { condition: service_healthy }
    redis: { condition: service_healthy }
```

**왜 `profiles: ["app"]`?**:
- 기본 `docker compose up -d` 는 mysql + redis 만 띄움 (개발자가 IDE 에서 앱 실행하는 흐름)
- 명시적으로 `docker compose --profile app up -d` 해야 앱도 띄움 (전체 컨테이너 환경)
- → 개발자가 IDE 사용 중일 때 두 개의 앱 인스턴스가 8080 포트 충돌 안 함

**언제 `--profile app` 쓰나**:
- AWS 배포 전 production-like 환경 검증
- 풀스택 / 프론트엔드 개발자에게 백엔드 컨테이너 통째로 제공
- 통합 테스트 / 부하 테스트의 외부 호출

---

## Dockerfile — 운영용 멀티스테이지 빌드

### 구조

```dockerfile
# Stage 1: Build
FROM bellsoft/liberica-openjdk-alpine:21 AS builder
WORKDIR /workspace
# gradle wrapper / 의존성 캐시 우선
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x ./gradlew && ./gradlew --version
# 소스 복사 후 bootJar
COPY src ./src
RUN ./gradlew bootJar -x test --no-daemon

# Stage 2: Runtime
FROM bellsoft/liberica-openjre-alpine:21
RUN addgroup -S app && adduser -S -G app app
WORKDIR /app
COPY --from=builder /workspace/build/libs/*-SNAPSHOT.jar app.jar
RUN chown app:app app.jar
USER app
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Seoul"
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=5 \
    CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
```

### 결정 — 멀티스테이지

**왜 두 단계?**:
- **Stage 1 (builder)**: Gradle + JDK 21 — Gradle 캐시 / 소스 / 빌드 산출물 모두 들어감 (~1.5 GB)
- **Stage 2 (runtime)**: JRE 만 — slim alpine (~180 MB). Gradle / 소스 / 빌드 캐시 다 버림
- 결과: **최종 이미지 ~180 MB** — ECR push / EC2 pull 빠름

**대안**:
- Distroless (Google) — 더 작지만 디버깅 어려움 (shell 없음)
- Buildpacks (`spring-boot:build-image`) — 자동화 강하지만 커스터마이즈 어려움
- 본 프로젝트: 명시적 control + 디버깅 가능성 우선 → 멀티스테이지 + Alpine

### 결정 — bellsoft/liberica

**왜 Liberica?**:
- 무료 / 오픈 / TCK 인증 (Java 표준 호환)
- Alpine 변형 있음 (slim)
- 성능 안정 (HotSpot 기반)

**대안**:
- Amazon Corretto — AWS 가 만든 OpenJDK. EC2 / Lambda 와 통합. 본 프로젝트도 가능
- Eclipse Temurin (이전 AdoptOpenJDK) — 무난. Alpine 지원
- 본 프로젝트: 인터뷰 답변 위해 Liberica — "BellSoft 의 Liberica 이고 alpine variant 라 ~180 MB"

### 결정 — 비-root 사용자

```dockerfile
RUN addgroup -S app && adduser -S -G app app
USER app
```

- 이유: 보안 기본 — 컨테이너 escape 공격 시 root 권한 회피
- Docker 의 best practice — 모든 production 이미지에 적용 권장
- 비용: 파일 permission 신경 써야 (`chown app:app app.jar`)

### 결정 — `-x test` 로 빌드

```dockerfile
RUN ./gradlew bootJar -x test --no-daemon
```

- 이유: **테스트는 CI 단계에서 이미 통과한 결과물을 빌드한다는 가정**. CI 에서 `./gradlew test` 가 fail 했으면 이미지 빌드 자체가 안 됐을 것
- 빌드 시간 ~40초 절감
- 위험: CI 우회해서 직접 빌드 시 테스트 안 도는 — `docker build` 만 하지 말고 `./gradlew test && docker build` 워크플로우 강제

### 결정 — `MaxRAMPercentage=75.0`

```dockerfile
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Seoul"
```

- 이유: 컨테이너 메모리 한도 인식. JVM 기본은 호스트 메모리 기준 → 컨테이너 안에선 OOM 위험
- `MaxRAMPercentage=75.0` → 컨테이너 메모리 한도의 75% 를 힙 최대로
- EC2 t3.micro = 1 GiB → 컨테이너 ~750 MB heap. 나머지 ~250 MB 는 OS / JVM metaspace / Direct buffer
- 운영에서 EC2 인스턴스 키우면 자동 비례

### 결정 — `HEALTHCHECK`

```dockerfile
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=5 \
    CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1
```

**왜 `wget` 인가?**:
- Alpine 기본에 `curl` 없음, `wget` 있음
- HEALTHCHECK 가 docker daemon 의 컨테이너 상태 (`unhealthy` 시 ALB 가 인스턴스 제외)

**timing 설정 근거**:
- `interval=10s` — 너무 자주 체크하면 의미 없는 부하
- `timeout=3s` — Spring Boot Actuator 응답 보통 <100ms, 3초면 충분
- `start-period=30s` — Spring Boot warmup 시간 (DB 연결 / Redis 연결 / Bean 초기화)
- `retries=5` — 5번 연속 실패해야 unhealthy → 일시 장애 흡수

---

## Docker workflow — 로컬 → ECR → EC2

### 1. 로컬 빌드 + 테스트

```bash
./gradlew test                          # 138 tests pass 확인
./gradlew bootJar -x test               # bootJar 생성 (build/libs/*.jar)
docker build -t usedtrade-app:local .   # 이미지 빌드
docker run --rm -p 8080:8080 \
  --env-file .env \
  --network usedtrade-network \
  usedtrade-app:local                    # 로컬 검증
```

### 2. ECR push

```bash
ECR_URI=970852255272.dkr.ecr.us-east-1.amazonaws.com/usedtrade

aws ecr get-login-password --region us-east-1 \
  | docker login --username AWS --password-stdin \
      "$(echo $ECR_URI | cut -d/ -f1)"

docker tag usedtrade-app:local "${ECR_URI}:latest"
docker tag usedtrade-app:local "${ECR_URI}:v1"
docker push "${ECR_URI}:v1"
docker push "${ECR_URI}:latest"
```

태그 전략:
- `:latest` — 가장 최근 (production deploy 가 가리킴)
- `:v1`, `:saga-v1` 등 — 의미 있는 마일스톤 (롤백 용)

### 3. EC2 에서 pull + run (user-data 스크립트)

EC2 launch 시 user-data:

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
  -e DB_HOST=<rds-endpoint> -e DB_USER=admin -e DB_PASSWORD=... \
  -e REDIS_HOST=<redis-endpoint> -e REDIS_PORT=6379 -e REDIS_PASSWORD= \
  -e JWT_SECRET=... \
  <ECR-uri>:latest
```

핵심 결정:
- **EC2 launch 마다 user-data 새로 작성** — RDS / Redis endpoint 가 매번 다를 수 있어 (특히 ElastiCache 재생성 시)
- `--restart unless-stopped` — EC2 재시작 시 컨테이너 자동 기동, 명시적 `docker stop` 시엔 그대로
- IAM role (`usedtrade-ec2-role`) 에 `AmazonEC2ContainerRegistryReadOnly` 정책 → ECR 자격증명 자동

---

## 면접 대비

### Q. "Docker 어떻게 구성하셨어요?"

**A**: "두 파일로 분리:
- **docker-compose.yml**: 로컬 개발용 — MySQL + Redis 항상 띄우고, 앱 컨테이너는 `--profile app` 으로 선택적. 이 분리 덕에 IDE 로 앱 띄우면서 인프라만 컨테이너 가능
- **Dockerfile**: 운영용 멀티스테이지 — Stage 1 Gradle 빌드 (~1.5GB), Stage 2 slim Alpine JRE (~180MB). 최종 이미지 작아 ECR push / EC2 pull 빠름

비-root 사용자, MaxRAMPercentage=75, HEALTHCHECK 로 `/actuator/health` 체크. EC2 의 user-data 가 ECR 에서 pull + docker run — 인프라 추가 없이 컨테이너 기반 배포."

### Q. "왜 멀티스테이지인가요?"

**A**: "**최종 이미지 크기 절감** — 빌드 단계에서 Gradle / JDK / 소스가 다 필요한데, 런타임엔 JAR 만 있으면 됨. 단일 stage 면 ~1.5GB 가 그대로 ECR / EC2 로 push/pull 됨. 멀티스테이지로 ~180MB. ECR 비용 (저장 GB / 월) 감소 + EC2 pull 속도 ↑ + 보안 surface 줄음 (빌드 도구 / 소스 코드가 운영 이미지에 없음)."

### Q. "MaxRAMPercentage 가 뭐예요?"

**A**: "JVM 의 힙 메모리 한도를 **컨테이너 메모리 한도의 N%** 로 설정. 본 프로젝트는 75% — EC2 t3.micro (1 GiB) → 컨테이너 메모리 한도 1 GiB → 힙 최대 ~750 MB. 나머지 ~250 MB 는 OS / JVM metaspace / Direct buffer. JVM 기본은 호스트 메모리 기준이라 컨테이너 안에선 OOM 위험 — `MaxRAMPercentage` 가 컨테이너 환경 인식 토글."

### Q. "Healthcheck 가 왜 ALB 헬스체크랑 다른 경로인가요?"

**A**: "**같은 경로** (`/actuator/health`) 입니다. 두 layer 에서 같은 헬스체크를 보는 게 의도:
1. Docker HEALTHCHECK — 컨테이너 자체의 상태 (Docker 가 unhealthy 면 restart 정책에 따라 자동 재시작)
2. ALB Target Group 헬스체크 — ALB 가 인스턴스를 라우팅에서 제외할지

두 layer 가 같은 endpoint 를 보면 일관성. 만약 Docker level 만 OK 인데 ALB level 이 fail 이면 SG / 네트워크 문제 의심."

### Q. "TestContainers 안 쓰면 통합 테스트 환경 어떻게 격리하나요?"

**A**: "통합 테스트 전 `docker compose up -d` 가 항상 깨끗한 MySQL / Redis 띄움 — 사실상 컨테이너 격리. 단점: 테스트 끼리 DB 데이터 공유 — 그래서 각 통합 테스트가 `@BeforeEach` 로 자기 데이터만 깔끔히 셋업. 환경이 더 복잡해지면 (LocalStack 같은 외부 의존 합류) TestContainers 도입."

---

## References

- 빌드 / 배포 절차: [`docs/deploy/aws-setup.md`](../deploy/aws-setup.md)
- 환경변수 템플릿: [`.env.example`](../../.env.example)
- Spring Boot 프로파일별 설정: [`src/main/resources/application-docker.yaml`](../../src/main/resources/application-docker.yaml)
