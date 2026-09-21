package com.team4.hanbbyeom.trust.dto;

import com.team4.hanbbyeom.matching.domain.TalkLevel;

import java.util.List;

// 신뢰 프로필 조회 응답 — 호스트/신청자 프로필 조회, 마이페이지 셀프 조회에서 공용으로 쓴다.
public record TrustProfileResponse(
        Double averageRating,   // 평가가 하나도 없으면 null
        Integer reviewCount,
        Integer completedCount,
        Integer noShowReportCount,

        // 체감 대화 수준 다수결 — review_silent_vote_count/review_light_chat_vote_count 중
        // 더 많은 쪽. 후기가 하나도 없거나(둘 다 0) 동률이면 null (이슈 #69)
        TalkLevel perceivedTalkLevelMajority,
        // 다수결로 뽑힌 쪽의 vote 수. perceivedTalkLevelMajority가 null이면 0
        Integer perceivedTalkLevelMajorityCount,

        // 최근 후기 목록 (activity_review.created_at 최신순, 최대 N건). 후기가 없으면 빈 리스트
        List<RecentReviewResponse> recentReviews
) {
}