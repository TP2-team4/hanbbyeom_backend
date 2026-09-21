package com.team4.hanbbyeom.auth.controller;

import com.team4.hanbbyeom.auth.dto.LoginRequest;
import com.team4.hanbbyeom.auth.dto.LoginResponse;
import com.team4.hanbbyeom.auth.dto.PasswordResetRequest;
import com.team4.hanbbyeom.auth.dto.SignUpRequest;
import com.team4.hanbbyeom.auth.dto.SignUpResponse;
import com.team4.hanbbyeom.auth.service.AuthService;
import com.team4.hanbbyeom.auth.service.PasswordResetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 회원가입, 로그인, 비밀번호 재설정 API
@Tag(name = "Auth", description = "회원가입, 로그인 및 비밀번호 재설정 API")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService; // Controller가 사용할 Service 객체
    private final PasswordResetService passwordResetService; // 비밀번호 재설정 Service 객체

    // 회원가입: POST /api/auth/signup
    @Operation(
            summary = "회원가입",
            description = "이메일 인증을 완료한 사용자를 가입 처리하고 생성된 사용자 정보를 반환합니다. "
                    + "이미 가입된 이메일이면 409이고, 입력값이 형식·길이 규칙을 어기면 400입니다."
    )
    @PostMapping("/signup")
    // AuthService에게 request(회원가입 요청 데이터)를 넘겨서 회원가입을 처리시키고,
    // 그 결과(SignUpResponse)를 response라는 변수에 저장
    public ResponseEntity<SignUpResponse> signUp(@Valid @RequestBody SignUpRequest request) {
        SignUpResponse response = authService.signUp(request);

        // body에 회원가입 결과(id, email, nickname, defaultTalkLevel)를 담아 200 OK 반환
        // 가입된 사용자 정보를 돌려줘야 하므로 ok가 아니라 ok(response)
        return ResponseEntity
                .ok(response);
    }

    // 로그인: POST /api/auth/login
    @Operation(
            summary = "로그인",
            description = "이메일과 비밀번호를 인증하고 이후 요청에 사용할 Access Token을 발급합니다."
    )
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        LoginResponse response = authService.login(request);

        // body에 발급된 Access Token을 담아 200 OK 반환
        return ResponseEntity
                .ok(response);
    }

    // 비밀번호 재설정: POST /api/auth/password-reset
    @Operation(
            summary = "비밀번호 재설정",
            description = "PASSWORD_RESET 이메일 인증을 완료한 사용자의 비밀번호를 변경합니다. "
                    + "사용자 존재 여부와 상세 인증 실패 원인은 구분해서 반환하지 않습니다."
    )
    @ApiResponse(responseCode = "204", description = "비밀번호 재설정 성공")
    @PostMapping("/password-reset")
    public ResponseEntity<Void> resetPassword(
            @Valid @RequestBody PasswordResetRequest request
    ) {
        passwordResetService.resetPassword(request);

        // 응답 본문 없는 204 No Content 반환
        return ResponseEntity
                .noContent()
                .build();
    }
}
