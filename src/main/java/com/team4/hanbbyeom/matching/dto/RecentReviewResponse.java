package com.team4.hanbbyeom.matching.dto;

import com.team4.hanbbyeom.matching.domain.TalkLevel;

import java.time.OffsetDateTime;

// 신뢰 프로필의 "최근 후기" 목록에 들어가는 후기 1건.
// 후기 내용만 보여주면 어떤 활동에서 받은 건지 알 수 없어서, activity_match를 조인해
// 코스명/거리 같은 활동 요약도 같이 내려준다 (이슈 #69 참고 사항).
public record RecentReviewResponse(
        Integer rating,
        TalkLevel perceivedTalkLevel,
        String comment,           // null이면 별점만 남긴 후기
        OffsetDateTime createdAt,

        // 이 후기가 어느 활동에 대한 것인지 요약
        String courseName,
        Integer distanceMinMeters,
        Integer distanceMaxMeters
) {
}