# syntax=docker/dockerfile:1
# Build stage: uses the Gradle wrapper so the image builds without any local toolchain.
FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace

COPY gradlew ./
COPY gradle gradle
COPY build.gradle settings.gradle ./
COPY src src
# BuildKit cache mount keeps the Gradle dependency cache across image builds.
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon bootJar -x test

# Runtime stage: JRE only.
FROM eclipse-temurin:17-jre
WORKDIR /app
RUN useradd --system --no-create-home appuser
USER appuser
COPY --from=build /workspace/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
