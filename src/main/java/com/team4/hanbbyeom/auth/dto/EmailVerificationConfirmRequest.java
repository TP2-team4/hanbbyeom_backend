package com.team4.hanbbyeom.auth.dto;

import com.team4.hanbbyeom.auth.domain.VerificationPurpose;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

// 인증 코드 확인 요청 시 클라이언트가 보내는 데이터 DTO (이메일, 인증 목적, 입력한 코드)
@Schema(description = "이메일 인증 코드 확인 요청. 발송 요청과 같은 이메일·목적으로 보내야 합니다.")
public record EmailVerificationConfirmRequest(

        @NotBlank
        @Email
        @Size(max = 255)
        @Schema(description = "인증 코드를 받은 이메일", example = "runner@example.com")
        String email,

        @NotNull
        @Schema(description = "인증 목적. 발송 요청에 사용한 값과 같아야 합니다.", example = "SIGNUP")
        VerificationPurpose purpose,

        @NotBlank
        @Pattern(regexp = "^[0-9]{6}$") // 6자리 숫자 형식만 허용
        @Schema(description = "메일로 받은 6자리 숫자 인증 코드", example = "123456")
        String code
) {
}
