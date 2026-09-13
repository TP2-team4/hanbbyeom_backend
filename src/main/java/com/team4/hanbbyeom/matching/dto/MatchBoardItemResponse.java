package com.team4.hanbbyeom.matching.dto;

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
        boolean isApplied,
        AuthorSummary author
) {
    public record AuthorSummary(
            String nickname,
            Double rating,        // TODO: 신뢰 프로필 도메인 연동 전까지는 null
            Integer completedCount // TODO: 위와 동일
    ) {}
}