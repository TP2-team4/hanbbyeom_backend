package com.team4.hanbbyeom.user.dto;

import com.team4.hanbbyeom.user.domain.DefaultTalkLevel;
import com.team4.hanbbyeom.user.domain.User;
import io.swagger.v3.oas.annotations.media.Schema;

// 내 정보 조회(GET /api/users/me) 응답 데이터

// User Entity를 그대로 반환하지 않고 DTO로 변환하는 이유
// → Entity에는 passwordHash, deletedAt처럼 외부에 나가면 안 되는 값이 같이 들어 있음
// → 응답에 내보낼 필드를 여기에 직접 적어두면, 나중에 Entity에 민감한 필드가 추가돼도
//   이 DTO를 고치지 않는 한 응답에 자동으로 딸려 나가지 않음

// SignUpResponse와 필드 구성이 같지만 재사용하지 않고 따로 만든 이유
// → 회원가입 응답과 내 정보 조회 응답은 서로 다른 API 약속(계약)이라,
//   한쪽 요구사항이 바뀌었을 때 다른 쪽까지 같이 바뀌어버리면 안 됨
@Schema(description = "내 정보 조회 응답. Access Token의 소유자 본인 정보만 반환됩니다.")
public record UserResponse(

        @Schema(description = "사용자 식별자", example = "1")
        Long id,

        @Schema(description = "가입된 이메일", example = "runner@example.com")
        String email,

        @Schema(description = "서비스에서 표시되는 닉네임", example = "한뼘러너")
        String nickname,

        // 마이페이지와 모집글 작성 화면에서 현재 사용자 설정을 사용할 수 있도록 반환
        @Schema(description = "모집글 작성 시 기본으로 사용할 대화 수준",
                example = "SILENT", allowableValues = {"SILENT", "LIGHT_CHAT"})
        DefaultTalkLevel defaultTalkLevel
) {

    // from(): User Entity를 받아서 UserResponse DTO로 변환하는 정적 메서드
    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getNickname(),
                user.getDefaultTalkLevel()
        );
    }
}
