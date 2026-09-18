package com.team4.hanbbyeom.feedback.dto;

import com.team4.hanbbyeom.feedback.domain.NoShowReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// 노쇼 신고 접수 요청 DTO
public record NoShowReportCreateRequest(

        // 신고 사유 (필수) — NOT_SHOWED_UP | LEFT_WITHOUT_NOTICE | OTHER 중 하나
        @NotNull
        NoShowReason reason,

        // 상세 내용 (선택, 최대 500자)
        @Size(max = 500)
        String detail
) {
}