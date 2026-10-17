
# syntax=docker/dockerfile:1

# Build stage
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /app

# Copy dependency metadata first for better Docker layer caching
COPY pom.xml .

# Download dependencies with retries
RUN --mount=type=cache,target=/root/.m2/repository \
    sh -c 'for i in 1 2 3; do \
      mvn -B -ntp dependency:go-offline && break; \
      if [ "$i" -eq 3 ]; then exit 1; fi; \
      sleep $((i * 20)); \
    done'

# Copy source only after dependency resolution
COPY src ./src

# Compile and package the application
RUN --mount=type=cache,target=/root/.m2/repository \
    sh -c 'for i in 1 2 3; do \
      mvn -B -ntp -DskipTests package && break; \
      if [ "$i" -eq 3 ]; then exit 1; fi; \
      sleep $((i * 20)); \
    done'

# Runtime stage
FROM eclipse-temurin:17-jre-alpine AS runtime

WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

ENV PORT=8080

EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
