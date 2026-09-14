package com.team4.hanbbyeom.auth.controller;

import com.team4.hanbbyeom.auth.dto.SignUpRequest;
import com.team4.hanbbyeom.auth.dto.SignUpResponse;
import com.team4.hanbbyeom.auth.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 회원가입, 로그인 API
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService; // Controller가 사용할 Service 객체

    // 회원가입: POST /api/auth/signup
    @PostMapping("/signup")
    // AuthService에게 request(회원가입 요청 데이터)를 넘겨서 회원가입을 처리시키고,
    // 그 결과(SignUpResponse)를 response라는 변수에 저장
    public ResponseEntity<SignUpResponse> signUp(@Valid @RequestBody SignUpRequest request) {
        SignUpResponse response = authService.signUp(request);

        // body에 회원가입 결과(id, email, nickname)를 담아 200 OK 반환
        // 가입된 사용자 정보를 돌려줘야 하므로 ok가 아니라 ok(response)
        return ResponseEntity
                .ok(response);
    }
}
