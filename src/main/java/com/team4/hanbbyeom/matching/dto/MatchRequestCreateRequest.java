package com.team4.hanbbyeom.matching.dto;

import java.time.OffsetDateTime;

public record MatchRequestCreateRequest(
        Long courseId,             // running_course.id
        String meetingPoint,
        Integer distanceMinMeters,
        Integer distanceMaxMeters,
        Integer paceMinSec,
        Integer paceMaxSec,
        OffsetDateTime scheduledAt,
        String talkLevel // "SILENT" | "LIGHT_CHAT"
) {}