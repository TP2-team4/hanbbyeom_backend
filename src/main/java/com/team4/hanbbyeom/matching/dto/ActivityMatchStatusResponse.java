package com.team4.hanbbyeom.matching.dto;

import java.time.OffsetDateTime;

// 신청자·호스트 양쪽이 공용으로 쓰는 매칭 건 상태 조회 응답.
// 특히 신청자 입장에서 "내 신청이 거절됐는지, 응답 시간이 지나 만료됐는지, 확정됐는지"를
// status 값으로 구분하기 위한 용도 — 기존엔 이걸 구분할 방법이 없었다(host의 match_request가
// REJECTED/EXPIRED 둘 다 그냥 SEARCHING으로 돌아가서 신청자 쪽에서 원인을 알 수 없었음).
//
// 채팅 이슈의 "매칭 요약 조회" API와 같은 엔드포인트(GET /api/matching/matches/{id})를
// 쓸 예정이므로, 새 필드가 필요하면 별도 응답/엔드포인트를 만들지 말고 이 record에 이어서
// 확장할 것(예: courseName, location, scheduledAt 등) — 팀 리뷰로 합의된 방향.
public record ActivityMatchStatusResponse(
        Long activityMatchId,
        String status, // "PROPOSED" | "CONFIRMED" | "REJECTED" | "EXPIRED" | "ENDED"
        String meetingCode, // CONFIRMED일 때만 값이 있음
        OffsetDateTime confirmedAt, // CONFIRMED일 때만
        OffsetDateTime closedAt // REJECTED/EXPIRED/ENDED일 때만
) {}
