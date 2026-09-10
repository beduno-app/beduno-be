FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN ./gradlew dependencies --no-daemon 2>/dev/null || true
COPY src ./src
RUN ./gradlew bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S beduno && adduser -S beduno -G beduno
COPY --from=build /app/build/libs/*.jar app.jar
RUN chown beduno:beduno app.jar
USER beduno
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=30s \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1
# Exec-form entrypoint means no shell, so a JAVA_OPTS env var would be silently ignored --
# the heap flag has to live here. Without it the container-aware JVM caps the heap at 25%
# of the container limit.
#
# 65, not 75: the percentage governs the heap alone, while the cgroup limit governs the whole
# process. Metaspace, code cache, thread stacks and direct buffers add roughly 250 MB for this
# application, so 75% of the 1024m limit in docker-compose.prod.yml leaves a heap ceiling that
# only just fits -- and the JVM does not throw OutOfMemoryError when it exceeds the cgroup, it is
# SIGKILLed, with no stack trace and nothing in the log to explain the restart.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=65", "-jar", "app.jar"]
