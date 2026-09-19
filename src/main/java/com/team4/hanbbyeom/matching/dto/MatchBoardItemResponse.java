package com.team4.hanbbyeom.matching.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

public record MatchBoardItemResponse(
        Long id,
        String courseName,
        Integer distanceMinMeters,
        Integer distanceMaxMeters,
        String talkLevel,
        OffsetDateTime scheduledAt,
        Integer paceMinSec,
        Integer paceMaxSec,
        AuthorSummary author
) {
    // 모집 목록·모집글 상세(author)·내 신청 내역(host)이 함께 쓰는 작성자 요약.
    // 상세와 내 신청 내역은 작성자가 탈퇴해도 조회되므로 nickname이 null일 수 있다(목록에서는 탈퇴한 작성자의 글을 제외).
    public record AuthorSummary(
            @Schema(description = "작성자 닉네임. 작성자가 탈퇴한 경우 null이며, 표시 문구는 클라이언트에서 정한다",
                    example = "한뼘러너", nullable = true)
            String nickname,
            Double rating,        // TODO: 신뢰 프로필 도메인 연동 전까지는 null
            Integer completedCount // TODO: 위와 동일
    ) {}
}