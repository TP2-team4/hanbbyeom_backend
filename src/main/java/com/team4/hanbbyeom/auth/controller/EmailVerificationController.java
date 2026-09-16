package com.team4.hanbbyeom.auth.controller;

import com.team4.hanbbyeom.auth.dto.EmailVerificationConfirmRequest;
import com.team4.hanbbyeom.auth.dto.EmailVerificationSendRequest;
import com.team4.hanbbyeom.auth.service.EmailVerificationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// HTTP로 들어온 요청을 어떻게 Service한테 전달하고, 결과를 어떤 응답 형태로 돌려줄지 작성 (Service는 변화X)

// 이메일 인증 코드 발송 및 확인 API
@RestController
@RequestMapping("/api/auth/email-verifications")
@RequiredArgsConstructor
public class EmailVerificationController {

    // Controller가 사용할 Service 객체
    // Controller(HTTP 요청/응답 담당), Service(비즈니스 로직 담당) 역할을 나눔
    private final EmailVerificationService emailVerificationService;

    // <Void>: 응답 body로 돌려줄 데이터가 없음
    // @RequestBody: HTTP 요청의 JSON body를 Java 객체로 변환

    // 인증 코드 발송 요청: POST /api/auth/email-verifications
    @PostMapping
    public ResponseEntity<Void> send(@Valid @RequestBody EmailVerificationSendRequest request) {
        emailVerificationService
                .sendVerificationCode(
                        request.email(), // 요청에서 받은 인증할 이메일
                        request.purpose() // 요청에서 받은 인증 목적
        );

        // HTTP Body 없이 200 OK 응답을 반환
        return ResponseEntity
                .ok() // 상태코드를 200 OK로 설정
                .build(); // 실제 ResponseEntity 객체 생성
    }

    // 인증 코드 확인: POST /api/auth/email-verifications/confirm
    @PostMapping("/confirm")
    public ResponseEntity<Void> confirm(@Valid @RequestBody EmailVerificationConfirmRequest request) {
        emailVerificationService
                .confirmCode(
                        request.email(), // 요청에서 받은 인증할 이메일
                        request.purpose(), // 요청에서 받은 인증 목적
                        request.code() // 사용자가 입력한 6자리 인증번호
        );

        return ResponseEntity
                .ok() // 상태코드를 200 OK로 설정
                .build(); // 실제 ResponseEntity 객체 생성
    }
}
