# Stage 1: Build with the same checksum-verified wrapper used by CI.
FROM docker.io/library/eclipse-temurin:25-jdk-alpine@sha256:1e20ead9eec7234db293f1913438090d533ed47c0b9e12dc8c7627a802a44ed6 AS build
WORKDIR /app

COPY gradlew build.gradle.kts settings.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY src ./src
RUN ./gradlew bootJar --no-daemon --console=plain -x test

# Stage 2: Use an APK-managed JRE so Trivy can identify Java runtime CVEs.
FROM docker.io/library/alpine:3.24@sha256:be2656061b536c1883c4034790e21d0a09c5aaaf51968b89368a3c0371ea2c9a
RUN apk add --no-cache openjdk25-jre-headless \
    && addgroup -S appgroup && adduser -S appuser -G appgroup
ENV JAVA_HOME=/usr/lib/jvm/java-25-openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"
WORKDIR /app

COPY --from=build /app/build/libs/scheduler-*.jar app.jar
USER appuser

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
