# 1단계: 빌드 — Gradle로 실행 가능한 jar(bootJar)를 만듭니다.
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app

# 의존성 캐시를 위해 소스보다 먼저 복사
COPY gradlew gradlew.bat build.gradle settings.gradle ./
COPY gradle gradle
RUN chmod +x gradlew

COPY src src
RUN ./gradlew clean bootJar -x test --no-daemon

# 2단계: 실행 — JDK 없이 JRE만으로 가볍게 실행
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
