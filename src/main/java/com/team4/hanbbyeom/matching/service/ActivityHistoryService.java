package com.team4.hanbbyeom.matching.service;

import com.team4.hanbbyeom.matching.domain.ActivityMatchStatus;
import com.team4.hanbbyeom.matching.dto.MyActivityResponse;
import com.team4.hanbbyeom.matching.repository.ActivityMatchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.util.List;

// 마이페이지의 내 활동 이력 조회를 담당한다. 읽기 전용이라 상태 전이나 matching_mutex 락이 없다.
@Service
public class ActivityHistoryService {

    private final ActivityMatchRepository activityMatchRepository;

    public ActivityHistoryService(ActivityMatchRepository activityMatchRepository) {
        this.activityMatchRepository = activityMatchRepository;
    }

    // 내 활동 이력 전체 목록 조회(GET /api/matching/matches) — 표시 상태 재매핑·필터링·페이지네이션은 하지 않고
    // 활동 시작 시각 최신순 전체 목록을 그대로 반환한다(내 모집글 목록 getMyPosts()와 같은 방식).
    @Transactional(readOnly = true)
    public List<MyActivityResponse> getMyActivities(Long userId) {
        return activityMatchRepository.findMyActivities(userId).stream()
                .map(row -> new MyActivityResponse(
                        row.getActivityMatchId(),
                        row.getCourseName(),
                        row.getDistanceMinMeters(),
                        row.getDistanceMaxMeters(),
                        row.getScheduledAt().atOffset(ZoneOffset.UTC),
                        row.getScheduledEndAt().atOffset(ZoneOffset.UTC),
                        row.getStatus(),
                        toCancelledBy(row.getStatus(), row.getClosedByUserId(), userId),
                        row.getCounterpartNickname(),
                        row.getSubmittedFeedbackType()
                ))
                .toList();
    }

    // 취소 주체를 내 시점으로 표현한다. CANCELLED가 아니면 null이다(ENDED 등도 closed_by_user_id가 NULL이라
    // 그대로 SYSTEM으로 내려주면 오해를 부른다).
    // 사용자가 직접 취소하면 activity_match.closed_by_user_id에 그 사용자 id가 채워지고(스키마 정의: 직접 행동으로
    // 닫은 사용자, 자동 처리는 NULL — ActivityMatch.cancelByParticipant()), 회원 탈퇴로 인한 취소는 NULL이다
    // (ActivityMatch.cancelByWithdrawal()).
    private String toCancelledBy(String status, Long closedByUserId, Long userId) {
        if (!ActivityMatchStatus.CANCELLED.name().equals(status)) {
            return null;
        }
        if (closedByUserId == null) {
            return "SYSTEM";
        }
        return closedByUserId.equals(userId) ? "ME" : "COUNTERPART";
    }
}
