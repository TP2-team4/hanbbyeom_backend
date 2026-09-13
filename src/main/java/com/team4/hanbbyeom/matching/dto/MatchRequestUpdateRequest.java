package com.team4.hanbbyeom.matching.dto;

import java.time.OffsetDateTime;

public record MatchRequestUpdateRequest(
        Long courseId,
        String meetingPoint,
        Integer distanceMinMeters,
        Integer distanceMaxMeters,
        Integer paceMinSec,
        Integer paceMaxSec,
        OffsetDateTime scheduledAt,
        String talkLevel
) {}