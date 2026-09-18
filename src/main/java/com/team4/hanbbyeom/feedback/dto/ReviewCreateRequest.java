package com.team4.hanbbyeom.feedback.dto;

import com.team4.hanbbyeom.matching.domain.TalkLevel;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// 활동 후기 작성 요청 DTO
public record ReviewCreateRequest(
    // 별점 (필수, 1~5)
    // DB의 chk_activity_review_rating CHECK 제약과 동일한 범위를 API 단에서도 먼저 검증
    @NotNull
    @Min(1)
    @Max(5)
    Integer rating,

    // 체감 대화 수준 (필수) — SILENT | LIGHT_CHAT
    @NotNull
    TalkLevel perceivedTalkLevel,

    // 한 줄 후기 (선택, 최대 100자)
    // null이면 후기 없이 별점+대화수준만 등록
    @Size(max = 100)
    String comment
) {
}