package com.team4.hanbbyeom.auth.dto;

import com.team4.hanbbyeom.global.validation.Utf8ByteLength;
import com.team4.hanbbyeom.user.domain.DefaultTalkLevel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// 회원가입 요청 시 클라이언트가 보내는 요청 데이터: 이메일, 비밀번호, 닉네임, 기본 대화 수준

// @Schema: (Swagger 제공) API 문서에 표시할 설명과 예시를 지정
// → 주석은 코드를 읽는 개발자용, @Schema는 API 문서를 보는 프론트엔드용이라 내용이 다름
@Schema(description = "회원가입 요청. 이메일 인증을 먼저 완료한 이메일만 가입할 수 있습니다.")
public record SignUpRequest(

        // @NotBlank: null, "", "   " 차단
        // @Size: 문자열 길이 제한

        @NotBlank
        @Email // 이메일 형식 검증 (존재여부 검증X)
        @Size(max = 255)
        @Schema(description = "가입할 이메일. 앞뒤 공백은 제거되고 소문자로 변환되어 저장됩니다.",
                example = "runner@example.com")
        String email,

        @NotBlank
        @Size(min = 8, max = 64)
        @Utf8ByteLength(
                max = 72,
                message = "비밀번호가 허용 길이를 초과했습니다. 더 짧게 작성해주세요."
        )
        @Schema(description = "비밀번호 (8자 이상 64자 이하, UTF-8 기준 72바이트 이하)", format = "password",
                example = "password1234")
        String password,

        @NotBlank(message = "닉네임을 입력해주세요.")
        @Size(min = 2, max = 16, message = "닉네임은 2자 이상 16자 이하로 입력해주세요.") // 서비스 정책상 좁게 제한, DB(50자)는 향후 정책 변경 대비 여유값이라 DTO보다 넉넉
        @Schema(description = "서비스에서 표시할 닉네임 (2자 이상 16자 이하)", example = "한뼘러너")
        String nickname,

        // 화면의 기본 선택값과 별개로, 회원가입 요청에서 사용자의 선택을 명시적으로 전달받음
        @NotNull
        @Schema(description = "모집글 작성 시 기본으로 사용할 대화 수준",
                example = "SILENT", allowableValues = {"SILENT", "LIGHT_CHAT"})
        DefaultTalkLevel defaultTalkLevel
) {
}
