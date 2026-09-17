package com.team4.hanbbyeom.matching.dto;

import java.time.OffsetDateTime;

// 호스트 수락(accept) 성공 응답 - 확정된 매칭 id, 현장 확인용 6자리 코드, 확정 시각을 담는다.
public record MatchConfirmResponse(
        Long activityMatchId,
        String meetingCode,
        OffsetDateTime confirmedAt
) {}
