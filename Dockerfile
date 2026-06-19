FROM gradle:jdk25-graal AS builder

WORKDIR /app

COPY build.gradle.kts settings.gradle.kts ./
RUN gradle dependencies --no-daemon || true

# 소스 복사 후 빌드 (CI에서 테스트 분리 시 -x test)
COPY src ./src
RUN gradle clean bootJar --no-daemon

# =========================
# 2) Run Stage — Oracle GraalVM JDK 25 런타임
# =========================
FROM container-registry.oracle.com/graalvm/jdk:25

WORKDIR /app

# 멀티스테이지: builder의 부트 JAR만 복사 → 최종 이미지 경량화
COPY --from=builder /app/build/libs/*.jar app.jar

# ── JVM 옵션 (app 인스턴스 t3.medium: 2 vCPU / 4GB RAM + swap) ──
#  -XX:+UseJVMCICompiler : GraalVM JIT 사용(peak 성능)
#  -XX:MaxRAMPercentage=70 : 컨테이너 메모리의 70%까지 힙(4GB→약 2.8GB)
#  -XX:InitialRAMPercentage=30 : 시작 힙
#  -XX:+ExitOnOutOfMemoryError : OOM 시 즉시 종료 → docker restart로 자동 복구
#  -XX:+AlwaysPreTouch : 시작 시 힙 선커밋(응답시간 안정)
#  (1 vCPU/1GB로 줄이면 -XX:+UseSerialGC, MaxRAMPercentage=55 권장)
ENTRYPOINT ["java", \
  "-XX:+UseJVMCICompiler", \
  "-XX:MaxRAMPercentage=70", \
  "-XX:InitialRAMPercentage=30", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-XX:+AlwaysPreTouch", \
  "-Dspring.profiles.active=prod", \
  "-jar", "app.jar"]