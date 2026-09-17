package com.team4.hanbbyeom.matching.dto;

import java.time.OffsetDateTime;

// 호스트 본인 게시글(matchRequestId)에 온 대기 중인 신청 1건 조회 응답.
// activityMatchId만 넘겨주면 프론트가 이어서 applicant-profile 조회, accept/reject를 호출할 수 있다.
public record PendingApplicationResponse(
        Long activityMatchId,
        OffsetDateTime decisionExpiresAt
) {}
