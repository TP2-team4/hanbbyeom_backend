package com.team4.hanbbyeom.matching.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

public record MatchRequestResponse(
        Long id,
        String courseName,
        Integer distanceMinMeters,
        Integer distanceMaxMeters,
        Integer paceMinSec,
        Integer paceMaxSec,
        String meetingPoint,
        OffsetDateTime scheduledAt,
        String talkLevel,
        String status,
        boolean isOwner,
        long pendingApplicantCount,
        @Schema(description = "작성자 정보. 작성자가 탈퇴한 경우 author.nickname은 null")
        MatchBoardItemResponse.AuthorSummary author
) {}