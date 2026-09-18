package com.team4.hanbbyeom.matching.dto;

import java.time.OffsetDateTime;

// 신청자 본인이 지금까지 넣은 신청 내역 목록(GET /api/matching/board/applications) 한 건.
// status는 activity_match.status(PROPOSED/CONFIRMED/REJECTED/EXPIRED/ENDED)를 그대로 노출하지
// 않고, 화면(26)의 탭 구성에 맞춰 4가지로 재매핑한 값이다:
//   PENDING  : PROPOSED(호스트 응답 대기 중)
//   ACCEPTED : CONFIRMED 또는 ENDED(호스트가 수락 — 활동이 끝났어도 "수락됐다"는 사실은 그대로)
//   REJECTED : 호스트가 거절했거나(REJECTED, closedByUserId=호스트) 응답 기한을 넘겨 자동
//              만료(EXPIRED)된 경우 — 둘 다 "내 신청이 받아들여지지 않음"이라는 결과는 같음
//   CANCELLED: 신청자 본인이 직접 취소한 경우(REJECTED, closedByUserId=신청자 본인)
public record MyApplicationResponse(
        Long activityMatchId,
        String status, // "PENDING" | "ACCEPTED" | "REJECTED" | "CANCELLED"
        String courseName,
        Integer distanceMinMeters,
        Integer distanceMaxMeters,
        OffsetDateTime scheduledAt,
        String talkLevel,
        MatchBoardItemResponse.AuthorSummary host
) {}
