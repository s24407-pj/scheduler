# Stage 1: Build
FROM docker.io/library/gradle:9.8.2-jdk25 AS build
WORKDIR /app

COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY gradle ./gradle
RUN gradle dependencies --no-daemon || true

COPY src ./src
RUN gradle bootJar --no-daemon -x test

# Stage 2: Runtime
FROM docker.io/library/eclipse-temurin:25-jre-alpine
WORKDIR /app

COPY --from=build /app/build/libs/scheduler-*.jar app.jar

RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]



