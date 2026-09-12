package com.team4.hanbbyeom.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 회원가입 요청 시 클라이언트가 보내는 요청 데이터: 이메일, 비밀번호, 닉네임
public record SignUpRequest(

        // @NotBlank: null, "", "   " 차단
        // @Size: 문자열 길이 제한

        @NotBlank
        @Email // 이메일 형식 검증 (존재여부 검증X)
        @Size(max = 255)
        String email,

        @NotBlank
        @Size(min = 8, max = 64)
        String password,

        @NotBlank
        @Size(min = 2, max = 16) // 서비스 정책상 좁게 제한, DB(50자)는 향후 정책 변경 대비 여유값이라 DTO보다 넉넉
        String nickname
) {
}
