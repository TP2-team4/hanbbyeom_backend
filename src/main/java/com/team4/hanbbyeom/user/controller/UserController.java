package com.team4.hanbbyeom.user.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 사용자 정보 조회 API
@RestController
@RequestMapping("/api/users")
public class UserController {

    // 내 정보 조회: GET /api/users/me

    @GetMapping("/me")
    public ResponseEntity<String> me() {
        // 지금은 고정 응답(임시)이며, JWT 인증 필터를 붙인 뒤 실제 사용자 정보를 반환하도록 변경
        // SecurityConfig의 anyRequest().authenticated() 대상이므로(permitAll로 따로 열어놓지 않음) 인증 없이는 접근 불가
        // 보호된 엔드포인트(Protected Endpoint): 인증된 사용자만 접근할 수 있는 API
        // Spring Security는 Controller 앞에서 동작하므로, 인증되지 않은 요청은 이 Controller까지 도달 불가

        // (JWT 필터 구현 후) JWT Filter가 Access Token을 검증하고 Authentication을 SecurityContext에 등록하고,
        // 이에 따라 Spring Security가 해당 요청을 인증된 요청으로 처리하는지를 간단히 확인하기 위함!

        return ResponseEntity
                .ok("내 정보 조회 (임시 응답)");
    }
}
