package com.team4.hanbbyeom.auth.dto;

import com.team4.hanbbyeom.user.domain.DefaultTalkLevel;
import com.team4.hanbbyeom.user.domain.User;
import io.swagger.v3.oas.annotations.media.Schema;

// 회원가입 완료 후 클라이언트에 반환하는 응답 데이터 (passwordHash 제외)
@Schema(description = "회원가입 완료 응답. 비밀번호는 어떤 형태로도 포함되지 않습니다.")
public record SignUpResponse(

        @Schema(description = "가입된 사용자 식별자", example = "1")
        Long id,

        @Schema(description = "가입된 이메일 (정규화된 형태)", example = "runner@example.com")
        String email,

        @Schema(description = "가입된 닉네임", example = "한뼘러너")
        String nickname,

        // 회원가입 결과에 실제 저장된 설정값을 포함해 클라이언트가 확인할 수 있도록 함
        @Schema(description = "모집글 작성 시 기본으로 사용할 대화 수준",
                example = "SILENT", allowableValues = {"SILENT", "LIGHT_CHAT"})
        DefaultTalkLevel defaultTalkLevel
) {

    // from() 정의: User Entity를 받아서 SignUpResponse DTO로 변환하는 정적 메서드
    public static SignUpResponse from(User user) {
        return new SignUpResponse(
                user.getId(), // DB에 저장되면서 자동 생성된 User Entity의 id 값
                user.getEmail(),
                user.getNickname(),
                user.getDefaultTalkLevel()
        );
    }
}
