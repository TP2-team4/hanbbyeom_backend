package com.team4.hanbbyeom.user.dto;

import com.team4.hanbbyeom.user.domain.DefaultTalkLevel;
import com.team4.hanbbyeom.user.domain.User;
import io.swagger.v3.oas.annotations.media.Schema;

// 설정 변경 후 실제로 저장된 사용자 기본 설정을 반환하는 응답 데이터
@Schema(description = "사용자 기본 설정 응답")
public record UserPreferencesResponse(

        @Schema(description = "모집글 작성 시 기본으로 사용할 대화 수준",
                example = "LIGHT_CHAT", allowableValues = {"SILENT", "LIGHT_CHAT"})
        DefaultTalkLevel defaultTalkLevel
) {

    // 변경된 User Entity에서 외부에 공개할 설정값만 응답 DTO로 변환
    public static UserPreferencesResponse from(User user) {
        return new UserPreferencesResponse(user.getDefaultTalkLevel());
    }
}
