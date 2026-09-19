package com.team4.hanbbyeom.auth.dto;

import com.team4.hanbbyeom.global.validation.Utf8ByteLength;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 로그인 요청 시 클라이언트가 보내는 요청 데이터: 이메일, 비밀번호
@Schema(description = "로그인 요청. 실패 시 이메일과 비밀번호 중 무엇이 틀렸는지는 구분해서 알려주지 않습니다.")
public record LoginRequest(

        @NotBlank
        @Email // 이메일 형식 검증 (존재여부 검증X)
        @Size(max = 255)
        @Schema(description = "가입한 이메일. 대소문자와 앞뒤 공백은 무시됩니다.",
                example = "runner@example.com")
        String email,

        // 로그인에서는 기존 계정과의 호환성을 위해
        // 회원가입 시점의 최소 비밀번호 길이 정책을 다시 검증하지 않음
        // → 정책이 8자에서 12자로 바뀌어도, 이미 8자로 가입한 사용자는 로그인할 수 있어야 함
        @NotBlank
        @Utf8ByteLength(
                max = 72,
                message = "비밀번호가 허용 길이를 초과했습니다. 더 짧게 작성해주세요."
        )
        @Schema(description = "가입할 때 설정한 비밀번호 (UTF-8 기준 72바이트 이하)", format = "password",
                example = "password1234")
        String password
) {
}
