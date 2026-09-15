package com.team4.hanbbyeom.auth.dto;

import com.team4.hanbbyeom.auth.domain.VerificationPurpose;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

// 인증 코드 발송 요청 시 클라이언트가 보내는 데이터 DTO (이메일과 인증 목적)
public record EmailVerificationSendRequest(

        @NotBlank
        @Email
        String email,

        @NotNull
        VerificationPurpose purpose
) {
}
