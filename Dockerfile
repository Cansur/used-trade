# ===================================================================
# used-trade — 멀티스테이지 Dockerfile
# Stage 1: gradle build → bootJar 생성
# Stage 2: liberica JRE 21 (alpine) 런타임 — slim 이미지
# ===================================================================

# ---------- Stage 1: Build ----------
FROM bellsoft/liberica-openjdk-alpine:21 AS builder

WORKDIR /workspace

# gradle wrapper 부터 복사 — 의존성 다운로드 캐시 효과
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x ./gradlew && ./gradlew --version

# 소스 복사 후 bootJar — 테스트는 CI 에서 이미 통과한 결과물을 빌드한다는 가정 (-x test)
COPY src ./src
RUN ./gradlew bootJar -x test --no-daemon


# ---------- Stage 2: Runtime ----------
FROM bellsoft/liberica-openjre-alpine:21

# 비-root 사용자 — 보안 기본
RUN addgroup -S app && adduser -S -G app app

WORKDIR /app

# 빌드 산출물만 복사 (workspace 내 다른 산출물은 가져오지 않음)
COPY --from=builder /workspace/build/libs/*-SNAPSHOT.jar app.jar
RUN chown app:app app.jar

USER app

EXPOSE 8080

# JVM 옵션 — 컨테이너 메모리 한도 인식 + UTF-8 + 시간대 KST
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Seoul"

# Spring Boot actuator health endpoint 로 헬스체크 (운영에선 ALB 가 동일 경로 사용)
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=5 \
    CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
