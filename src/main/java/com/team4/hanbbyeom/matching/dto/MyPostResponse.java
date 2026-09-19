package com.team4.hanbbyeom.matching.dto;

import java.time.OffsetDateTime;

// 내가 등록한 모집글 전체 목록(GET /api/matching/requests) 한 건. status는 재매핑 없이
// match_request.status(SEARCHING/PENDING_CONFIRMATION/MATCHED/CANCELLED/EXPIRED/CLOSED)를
// 그대로 노출한다 — 화면 탭(모집 중/마감/취소) 필터링은 프론트가 담당하기로 함.
public record MyPostResponse(
        Long id,
        String courseName,
        Integer distanceMinMeters,
        Integer distanceMaxMeters,
        OffsetDateTime scheduledAt,
        String talkLevel,
        String status
) {}
