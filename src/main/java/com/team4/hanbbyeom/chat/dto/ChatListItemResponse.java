package com.team4.hanbbyeom.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

// 채팅 목록 조회 후 클라이언트에 반환하는 채팅방별 요약 데이터
@Schema(description = "채팅 목록 항목 응답")
public record ChatListItemResponse(
        @Schema(description = "채팅방으로 사용하는 매칭 ID", example = "12")
        Long activityMatchId,

        @Schema(description = "상대 사용자 ID", example = "8")
        Long counterpartUserId,

        @Schema(description = "매칭 상태", example = "CONFIRMED")
        String status,

        @Schema(description = "러닝 코스명", example = "뚝섬 한강공원")
        String courseName,

        @Schema(description = "만남 장소", example = "뚝섬유원지역 3번 출구")
        String location,

        @Schema(description = "활동 예정 시작 시각")
        OffsetDateTime scheduledAt,

        @Schema(description = "활동 예정 종료 시각")
        OffsetDateTime scheduledEndAt,

        @Schema(description = "최근 메시지 내용. 메시지가 없으면 null", nullable = true)
        String lastMessage,

        @Schema(description = "최근 메시지 생성 시각. 메시지가 없으면 null", nullable = true)
        OffsetDateTime lastMessageAt
) {
}
