package com.team4.hanbbyeom.matching.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

// 내 활동 이력 목록(GET /api/matching/matches) 한 건. 내 모집글 목록(MyPostResponse)과 같이 표시 상태로
// 재매핑하지 않고 원본 상태와 후기·신고 제출 여부만 내려준다 — 화면 칩(예정/진행 중/후기 작성 필요/완료/취소/
// 노쇼 신고)과 탭 필터링은 프론트가 담당하기로 함. 판정 기준은 이슈 #87의 "프론트엔드 표시 상태 매핑 가이드" 참고.
@Schema(description = "내 활동 이력 한 건. 한 번이라도 확정된 매칭만 포함됩니다.")
public record MyActivityResponse(
        @Schema(description = "매칭 id. 후기 작성·채팅 등 이 활동에 대한 다른 API의 경로 변수로 사용", example = "12")
        Long activityMatchId,

        @Schema(description = "코스명", example = "뚝섬 한강공원")
        String courseName,

        @Schema(description = "거리 범위 하한(m)", example = "5000")
        Integer distanceMinMeters,

        @Schema(description = "거리 범위 상한(m)", example = "12000")
        Integer distanceMaxMeters,

        @Schema(description = "활동 시작 시각")
        OffsetDateTime scheduledAt,

        @Schema(description = "활동 예정 종료 시각")
        OffsetDateTime scheduledEndAt,

        @Schema(description = "매칭 원본 상태. 확정된 적 있는 매칭이므로 CONFIRMED / ENDED / CANCELLED 중 하나. "
                + "종료 처리는 1분 주기 스케줄러가 하므로 종료 시각 직후 최대 1분간은 CONFIRMED로 남을 수 있어 "
                + "scheduledEndAt으로도 종료 여부를 판정해야 합니다",
                example = "ENDED", allowableValues = {"CONFIRMED", "ENDED", "CANCELLED"})
        String status,

        @Schema(description = "취소 주체. status가 CANCELLED일 때만 값이 있고 그 외에는 null. "
                + "ME는 내가 취소, COUNTERPART는 상대가 직접 취소, SYSTEM은 사용자의 직접 행동이 아닌 시스템 처리입니다"
                + "(현재 CANCELLED는 상대의 회원 탈퇴로 시스템이 취소하는 경우뿐이라 SYSTEM만 내려옵니다). "
                + "프론트는 counterpartNickname이 null인지로 취소 사유를 추론하지 말고 이 값을 사용하세요",
                example = "SYSTEM", allowableValues = {"ME", "COUNTERPART", "SYSTEM"}, nullable = true)
        String cancelledBy,

        @Schema(description = "상대 닉네임. 상대가 탈퇴한 경우 null", example = "조용한러너", nullable = true)
        String counterpartNickname,

        @Schema(description = "내가 이 활동에 제출한 것. 후기는 REVIEW, 노쇼 신고는 NO_SHOW_REPORT, 아무것도 제출하지 않았으면 null. "
                + "후기와 신고는 활동당 하나만 제출할 수 있으며 기존 feedback-status API의 submittedType과 같은 값입니다",
                example = "REVIEW", allowableValues = {"REVIEW", "NO_SHOW_REPORT"}, nullable = true)
        String submittedFeedbackType
) {}
