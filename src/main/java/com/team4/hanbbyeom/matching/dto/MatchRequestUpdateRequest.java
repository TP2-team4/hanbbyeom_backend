package com.team4.hanbbyeom.matching.dto;

import java.time.OffsetDateTime;

public record MatchRequestUpdateRequest(
        OffsetDateTime scheduledAt,
        String talkLevel
) {}