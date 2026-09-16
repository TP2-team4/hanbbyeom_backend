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
    // SecurityConfig의 anyRequest().authenticated() 대상이므로 인증 없이는 접근 불가
    // 지금은 고정 응답(임시)이며, JWT 인증 필터를 붙인 뒤 실제 사용자 정보를 반환하도록 변경
    @GetMapping("/me")
    public ResponseEntity<String> me() {
        return ResponseEntity
                .ok("내 정보 조회 (임시 응답)");
    }
}
