package com.team4.hanbbyeom.user.dto;

import com.team4.hanbbyeom.user.domain.DefaultTalkLevel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

// 마이페이지에서 사용자 기본 설정을 변경할 때 받는 요청 데이터
@Schema(description = "사용자 기본 설정 변경 요청")
public record UserPreferencesUpdateRequest(

        // 선택을 명시하지 않은 요청은 기존 값을 임의로 덮어쓰지 않도록 검증 단계에서 차단
        @NotNull
        @Schema(description = "모집글 작성 시 기본으로 사용할 대화 수준",
                example = "LIGHT_CHAT", allowableValues = {"SILENT", "LIGHT_CHAT"})
        DefaultTalkLevel defaultTalkLevel
) {
}
