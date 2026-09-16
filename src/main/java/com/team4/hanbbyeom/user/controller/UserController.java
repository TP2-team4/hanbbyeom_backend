package com.team4.hanbbyeom.user.controller;

import com.team4.hanbbyeom.global.security.CustomUserDetails;
import com.team4.hanbbyeom.user.dto.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 사용자 정보 조회 API
@Tag(name = "User", description = "사용자 정보 조회 API")
// Swagger UI에서 Authorize로 입력한 Bearer 토큰을 이 API 호출에 사용 (SwaggerConfig에 정의)
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/users")
public class UserController {

    // 내 정보 조회: GET /api/users/me

    // SecurityConfig의 anyRequest().authenticated() 대상이므로(permitAll로 따로 열어놓지 않음) 인증 없이는 접근 불가
    // 보호된 엔드포인트(Protected Endpoint): 인증된 사용자만 접근할 수 있는 API
    // Spring Security는 Controller 앞에서 동작하므로, 인증되지 않은 요청은 이 Controller까지 도달 불가

    // Service를 따로 두지 않은 이유
    // → 필터가 이미 조회해둔 사용자를 응답 DTO로 변환하기만 할 뿐 비즈니스 로직이 없음
    //   (회원 탈퇴(#7)처럼 실제 처리할 로직이 생기는 시점에 Service를 추가)
    @Operation(
            summary = "내 정보 조회",
            description = "인증이 필요한 내 정보 조회 엔드포인트입니다."
    )
    @GetMapping("/me")
    public ResponseEntity<UserResponse> me(
            // @AuthenticationPrincipal: (Spring Security 제공) SecurityContext에 저장된 인증 정보의
            // principal(현재 인증된 사용자)을 파라미터로 바로 꺼내주는 어노테이션
            // → JwtAuthenticationFilter가 토큰을 검증하고 넣어둔 CustomUserDetails가 그대로 전달됨
            //   (요청 헤더로 사용자 ID를 직접 받지 않으므로 다른 사람을 사칭할 수 없음)
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        // 필터가 DB에서 이미 조회해둔 User를 그대로 사용 (재조회 없음)
        return ResponseEntity
                .ok(UserResponse.from(principal.getUser()));
    }
}
