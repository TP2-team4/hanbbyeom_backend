package com.team4.hanbbyeom.auth.dto;

import com.team4.hanbbyeom.auth.domain.VerificationPurpose;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// 인증 코드 발송 요청 시 클라이언트가 보내는 데이터 DTO (이메일과 인증 목적)
@Schema(description = "이메일 인증 코드 발송 요청. 입력한 이메일로 6자리 인증 코드가 전송됩니다.")
public record EmailVerificationSendRequest(

        @NotBlank
        @Email
        @Size(max = 255)
        @Schema(description = "인증 코드를 받을 이메일", example = "runner@example.com")
        String email,

        @NotNull
        // enum이라 선택 가능한 값은 Swagger가 자동으로 함께 표시함
        @Schema(description = "인증 목적. SIGNUP은 회원가입용, PASSWORD_RESET은 비밀번호 재설정용입니다.",
                example = "SIGNUP")
        VerificationPurpose purpose
) {
}
