package com.team4.hanbbyeom.auth.dto;

import com.team4.hanbbyeom.auth.domain.VerificationPurpose;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

// 인증 코드 확인 요청 시 클라이언트가 보내는 데이터 DTO (이메일, 인증 목적, 입력한 코드)
public record EmailVerificationConfirmRequest(

        @NotBlank
        @Email
        String email,

        @NotNull
        VerificationPurpose purpose,

        @NotBlank
        @Pattern(regexp = "^[0-9]{6}$") // 6자리 숫자 형식만 허용
        String code
) {
}
