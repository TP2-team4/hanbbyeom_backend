package com.team4.hanbbyeom.auth.dto;

import com.team4.hanbbyeom.global.validation.Utf8ByteLength;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// 비밀번호 재설정 시 클라이언트가 보내는 요청 데이터: 이메일, 인증 코드, 새 비밀번호
@Schema(description = "비밀번호 재설정 요청. PASSWORD_RESET 이메일 인증 완료 후 사용할 수 있습니다.")
public record PasswordResetRequest(

        @NotBlank
        @Email
        @Size(max = 255)
        @Schema(description = "비밀번호를 재설정할 가입 이메일. 대소문자와 앞뒤 공백은 무시됩니다.",
                example = "runner@example.com")
        String email,

        @NotBlank
        @Pattern(regexp = "\\d{6}", message = "인증 코드는 6자리 숫자로 입력해주세요.")
        @Schema(description = "PASSWORD_RESET 목적으로 확인을 완료한 6자리 인증 코드",
                example = "123456")
        String code,

        @NotBlank
        @Size(min = 8, max = 64)
        @Utf8ByteLength(
                max = 72,
                message = "비밀번호가 허용 길이를 초과했습니다. 더 짧게 작성해주세요."
        )
        @Schema(description = "새 비밀번호 (8자 이상 64자 이하, UTF-8 기준 72바이트 이하)",
                format = "password", example = "newPassword1234")
        String newPassword
) {
}
