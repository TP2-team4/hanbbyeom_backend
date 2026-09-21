package com.team4.hanbbyeom.feedback.dto;

import com.team4.hanbbyeom.global.validation.NoNulCharacter;
import com.team4.hanbbyeom.matching.domain.TalkLevel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// 활동 후기 작성 요청 DTO
@Schema(description = "활동 후기 작성 요청")
public record ReviewCreateRequest(
    // 별점 (필수, 1~5)
    // DB의 chk_activity_review_rating CHECK 제약과 동일한 범위를 API 단에서도 먼저 검증
    @Schema(description = "별점", example = "5", minimum = "1", maximum = "5")
    @NotNull(message = "별점을 선택해주세요.")
    @Min(value = 1, message = "별점은 1점 이상이어야 합니다.")
    @Max(value = 5, message = "별점은 5점 이하여야 합니다.")
    Integer rating,

    // 체감 대화 수준 (필수) — SILENT | LIGHT_CHAT
    @Schema(description = "체감 대화 수준", example = "SILENT", allowableValues = {"SILENT", "LIGHT_CHAT"})
    @NotNull(message = "대화 수준을 선택해주세요.")
    TalkLevel perceivedTalkLevel,

    // 한 줄 후기 (선택, 최대 100자)
    // null이면 후기 없이 별점+대화수준만 등록
    @Schema(description = "한 줄 후기", example = "즐거운 러닝이었어요!", maxLength = 100)
    @Size(max = 100, message = "후기는 100자 이하여야 합니다.")
    @NoNulCharacter(message = "후기에 사용할 수 없는 문자가 포함되어 있어요.")
    String comment
) {
}