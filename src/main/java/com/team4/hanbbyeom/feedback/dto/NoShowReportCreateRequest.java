package com.team4.hanbbyeom.feedback.dto;

import com.team4.hanbbyeom.feedback.domain.NoShowReason;
import com.team4.hanbbyeom.global.validation.NoNulCharacter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// 노쇼 신고 접수 요청 DTO
@Schema(description = "노쇼 신고 접수 요청")
public record NoShowReportCreateRequest(

        // 신고 사유 (필수) — NOT_SHOWED_UP | LEFT_WITHOUT_NOTICE | OTHER 중 하나
        @Schema(description = "신고 사유", example = "NOT_SHOWED_UP",
                allowableValues = {"NOT_SHOWED_UP", "LEFT_WITHOUT_NOTICE", "OTHER"})
        @NotNull(message = "신고 사유를 선택해주세요.")
        NoShowReason reason,

        // 상세 내용 (선택, 최대 500자)
        @Schema(description = "상세 내용", example = "연락 없이 나오지 않았어요.", maxLength = 500)
        @Size(max = 500, message = "상세 내용은 500자 이하여야 합니다.")
        @NoNulCharacter(message = "상세 내용에 사용할 수 없는 문자가 포함되어 있어요.")
        String detail
) {
}