package com.team4.hanbbyeom.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

// application.yaml의 jwt.* 설정값을 그대로 옮겨 담는 객체

// @ConfigurationProperties: application.yaml의 설정값들을 Java 객체의 필드와 묶어주는 기능
// prefix: application.yaml에서 어떤 설정 묶음을 가져올지 지정 → jwt 밑에 있는 것만 가져오도록

// record는 필드가 final이라 setter가 없고, 생성자로만 값을 채울 수 있음
// Spring Boot는 이런 불변 객체에 Constructor Binding 방식 사용
// → Constructor Binding: 설정값을 생성자 파라미터에 그대로 연결해서 객체를 생성

// 문제: @Component를 같이 붙이면 Spring은 이 클래스를 일반 빈으로 인식하고,
//      생성자가 하나뿐이므로 그 생성자를 자동 주입 대상으로 삼음
// → secretBase64, issuer 같은 파라미터를 등록된 빈으로 착각해 주입하려다
//   해당 타입의 빈이 없어 NoSuchBeanDefinitionException 발생

// 해결: @Component 제거, @ConfigurationPropertiesScan으로만 등록
// → HanbbyeomApplication에서 @ConfigurationProperties 붙은 클래스를 찾아
//   설정값 바인딩 대상으로만 등록 (일반 빈 생성자 주입과는 다른 경로)
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(
        String secretBase64, // 토큰 서명용 비밀키 (.env의 JWT_SECRET_BASE64)
        String issuer, // 토큰 발급자 이름 (application.yaml에 "hanbbyeom"으로 고정)
        Duration accessTokenExpiration, // Access Token 유효기간
        Duration refreshTokenExpiration // Refresh Token 유효기간
) {
}
