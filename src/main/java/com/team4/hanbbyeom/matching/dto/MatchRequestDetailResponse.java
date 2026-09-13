package com.team4.hanbbyeom.matching.dto;

import java.time.OffsetDateTime;

public record MatchRequestDetailResponse(
        Long id,
        String courseName,
        Integer distanceMeters,
        Integer paceMinSec,
        Integer paceMaxSec,
        String meetingPoint,
        OffsetDateTime scheduledAt,
        String talkLevel,
        String status,
        boolean isOwner,
        long pendingApplicantCount,
        MatchBoardItemResponse.AuthorSummary author
) {}