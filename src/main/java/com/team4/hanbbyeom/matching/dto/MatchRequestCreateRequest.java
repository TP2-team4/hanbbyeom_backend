package com.team4.hanbbyeom.matching.dto;

import java.time.OffsetDateTime;

public record MatchRequestCreateRequest(
        Long runMatchConditionId,
        OffsetDateTime scheduledAt,
        String talkLevel // "SILENT" | "GREETING_ONLY" | "LIGHT_CHAT"
) {}