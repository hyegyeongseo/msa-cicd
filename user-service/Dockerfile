# Stage 1: Build
FROM gradle:jdk21 AS builder
WORKDIR /app

# Gradle Wrapper 포함
COPY gradlew .
COPY gradle ./gradle
COPY build.gradle settings.gradle ./
RUN chmod +x ./gradlew

# 의존성만 먼저 다운로드 (캐시 활용)
RUN ./gradlew dependencies -x test --no-daemon

# 소스 복사 후 bootJar 빌드
COPY src ./src
RUN ./gradlew bootJar -x test --no-daemon

# Stage 2: Runtime
FROM eclipse-temurin:21-jdk-noble
WORKDIR /app
COPY --from=builder /app/build/libs/*-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]