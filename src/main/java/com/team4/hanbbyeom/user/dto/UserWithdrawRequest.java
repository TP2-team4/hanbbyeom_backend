package com.team4.hanbbyeom.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

// 회원 탈퇴 시 본인 확인을 위해 클라이언트가 보내는 요청 데이터: 현재 비밀번호
@Schema(description = "회원 탈퇴 요청. 토큰이 탈취된 경우에도 본인만 탈퇴할 수 있도록 현재 비밀번호를 다시 확인합니다.")
public record UserWithdrawRequest(

        // 로그인과 동일하게 회원가입 시점의 비밀번호 길이 정책은 다시 검증하지 않음
        // (정책 변경 이전에 가입한 사용자도 탈퇴할 수 있어야 함)
        @NotBlank(message = "비밀번호를 입력해주세요.")
        @Schema(description = "현재 사용 중인 비밀번호", format = "password", example = "password1234")
        String password
) {
}
