package com.team4.hanbbyeom.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 마이페이지 설정에서 닉네임을 변경할 때 받는 요청 데이터
@Schema(description = "닉네임 변경 요청")
public record UserNicknameUpdateRequest(

        // 회원가입(SignUpRequest)과 동일 규칙 적용: 가입 때 되던 닉네임이 변경 때 막히거나 그 반대가 되는 것 방지
        // 서비스 정책상 좁게 제한하고 DB(50자)는 향후 정책 변경 대비 여유값
        // 중복은 회원가입과 동일하게 허용 (DB에 유니크 제약 없음)
        // 검증 실패 시 GlobalExceptionHandler가 첫 필드 오류의 메시지를 그대로 응답하므로 한글 메시지 지정
        @NotBlank(message = "닉네임을 입력해주세요.")
        @Size(min = 2, max = 16, message = "닉네임은 2자 이상 16자 이하로 입력해주세요.")
        @Schema(description = "변경할 닉네임 (2자 이상 16자 이하). 회원가입과 같은 규칙이며 다른 사용자와 중복될 수 있습니다.",
                example = "한뼘러너")
        String nickname
) {
}
