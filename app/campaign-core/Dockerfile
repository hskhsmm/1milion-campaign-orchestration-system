# 1. Build stage
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app

# gradle wrapper
COPY gradlew .
COPY gradle gradle
RUN chmod +x gradlew

# gradle 설정 (캐시)
COPY build.gradle* settings.gradle* ./
RUN ./gradlew dependencies --no-daemon || true

# 소스 코드만 복사
COPY src src

RUN ./gradlew clean bootJar --no-daemon

# 2. Run stage
FROM eclipse-temurin:25-jre
WORKDIR /app

# JAR 파일 복사
COPY --from=build /app/build/libs/campaign-core-*.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]
