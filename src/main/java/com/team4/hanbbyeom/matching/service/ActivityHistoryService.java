package com.team4.hanbbyeom.matching.service;

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
                        row.getCounterpartNickname(),
                        row.getSubmittedFeedbackType()
                ))
                .toList();
    }
}
