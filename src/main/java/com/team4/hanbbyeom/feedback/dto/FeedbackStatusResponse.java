package com.team4.hanbbyeom.feedback.dto;

// 이 매칭 건에 대해 현재 사용자가 후기/신고를 제출할 수 있는 상태인지 조회하는 응답 DTO
// (요청 DTO가 아니라 응답 DTO라 Bean Validation 어노테이션은 필요 없음)
public record FeedbackStatusResponse(

        // 지금 제출 가능한지 — 참가자이면서, 매칭이 확정된 적 있고(CONFIRMED 또는 ENDED),
        // 활동 종료 시각이 지났고, 아직 아무것도 제출 안 했을 때만 true
        boolean canSubmit,

        // 이미 후기나 신고를 제출했는지
        boolean alreadySubmitted,

        // 무엇을 제출했는지 — "REVIEW" | "NO_SHOW_REPORT" | null(아직 아무것도 안 함)
        String submittedType
) {
}