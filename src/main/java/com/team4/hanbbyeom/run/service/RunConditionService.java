package com.team4.hanbbyeom.run.service;

import com.team4.hanbbyeom.matching.domain.MatchRequest;
import com.team4.hanbbyeom.matching.exception.MatchRequestNotFoundException;
import com.team4.hanbbyeom.matching.repository.MatchRequestRepository;
import com.team4.hanbbyeom.run.domain.RunMatchCondition;
import com.team4.hanbbyeom.run.domain.RunningCourse;
import com.team4.hanbbyeom.run.dto.RunConditionCreateRequest;
import com.team4.hanbbyeom.run.repository.RunMatchConditionRepository;
import com.team4.hanbbyeom.run.repository.RunningCourseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RunConditionService {
    private static final int MIN_DISTANCE_METERS = 1000;
    private static final int MAX_DISTANCE_METERS = 20000;
    private static final int MIN_PACE_SEC = 300;
    private static final int MAX_PACE_SEC = 450;

    private final RunMatchConditionRepository runMatchConditionRepository;
    private final RunningCourseRepository runningCourseRepository;
    private final MatchRequestRepository matchRequestRepository;

    @Transactional
    public Long create(Long currentUserId, RunConditionCreateRequest request) {
        MatchRequest matchRequest = matchRequestRepository.findById(request.matchRequestId())
                .orElseThrow(() -> new MatchRequestNotFoundException(request.matchRequestId()));

        if (!matchRequest.isOwnedBy(currentUserId)) {
            throw new AccessDeniedException("본인 소유의 매칭 요청에만 조건을 등록할 수 있어요.");
        }

        RunningCourse runningCourse = runningCourseRepository.findById(request.courseId())
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 코스예요"));
        validateRange(request.distanceMinMeters(), request.distanceMaxMeters(),
                MIN_DISTANCE_METERS, MAX_DISTANCE_METERS, "거리");

        validateRange(request.paceMinSec(), request.paceMaxSec(),
                MIN_PACE_SEC, MAX_PACE_SEC, "페이스");

        RunMatchCondition condition = RunMatchCondition.builder()
                .matchRequest(matchRequest)
                .runningCourse(runningCourse)
                .meetingPoint(request.meetingPoint())
                .distanceMinMeters(request.distanceMinMeters())
                .distanceMaxMeters(request.distanceMaxMeters())
                .paceMinSec(request.paceMinSec())
                .paceMaxSec(request.paceMaxSec())
                .build();

        return runMatchConditionRepository.save(condition).getMatchRequestId();
    }
    private void validateRange(Integer min, Integer max, int allowedMin, int allowedMax, String label) {
        if (min < allowedMin || max > allowedMax) {
            throw new IllegalArgumentException(label + "는 " + allowedMin + "~" + allowedMax + " 범위 안이어야 해요.");
        }
        if (min > max) {
            throw new IllegalArgumentException(label + " 최소값이 최대값보다 클 수 없어요.");
        }
    }
}
