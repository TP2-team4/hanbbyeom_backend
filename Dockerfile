# 1단계(builder): 소스를 컴파일해서 실행 가능한 jar를 만드는 단계
# JDK가 필요하지만, 빌드가 끝나면 이 단계의 결과물은 jar 하나만 쓰고 버림
FROM eclipse-temurin:17-jdk AS builder
WORKDIR /app

# 의존성 목록(build.gradle 등)을 먼저 복사해서 의존성 다운로드 레이어를 캐시
# → src만 바뀐 재빌드에서는 의존성을 다시 받지 않아 빌드가 빨라짐
COPY gradlew ./
COPY gradle gradle
COPY build.gradle settings.gradle ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon || true

COPY src src
# 테스트는 DB가 필요하므로 이미지 빌드에서는 제외(검증은 GitHub Actions CI가 담당)
RUN ./gradlew bootJar --no-daemon -x test \
    && find build/libs -name '*.jar' ! -name '*plain*' -exec cp {} /app/app.jar \;

# 2단계(runtime): 실제 서버에서 돌아가는 이미지
# JDK 대신 JRE만 담아 이미지 용량과 공격 표면을 줄임
FROM eclipse-temurin:17-jre
WORKDIR /app

# root가 아닌 일반 사용자로 실행(컨테이너가 뚫려도 권한을 제한)
RUN useradd --create-home --shell /bin/bash spring
USER spring

COPY --from=builder --chown=spring:spring /app/app.jar app.jar

EXPOSE 8080

# MaxRAMPercentage: 컨테이너에 허용된 메모리의 75%까지만 힙으로 사용
# (EC2 프리티어처럼 메모리가 작은 인스턴스에서 OOM으로 죽는 것을 방지)
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
