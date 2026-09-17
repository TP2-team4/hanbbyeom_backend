package com.team4.hanbbyeom.global.config;

import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

// Swagger UI에 JWT 입력 방식을 정의하고, 적용 대상은 각 보호 Controller에서 지정
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT",
        description = "로그인 API에서 발급받은 Access Token을 입력합니다. Bearer 접두사는 Swagger UI가 자동으로 추가합니다."
)
@Configuration
public class SwaggerConfig {
}
