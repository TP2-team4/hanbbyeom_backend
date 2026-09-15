package com.team4.hanbbyeom.run.service;

import com.team4.hanbbyeom.matching.domain.MatchRequest;
import com.team4.hanbbyeom.matching.domain.MatchRequestStatus;
import com.team4.hanbbyeom.matching.exception.MatchRequestNotFoundException;
import com.team4.hanbbyeom.matching.repository.MatchRequestRepository;
import com.team4.hanbbyeom.run.domain.RunMatchCondition;
import com.team4.hanbbyeom.run.domain.RunningCourse;
import com.team4.hanbbyeom.run.dto.RunConditionCreateRequest;
import com.team4.hanbbyeom.run.dto.RunConditionResponse;
import com.team4.hanbbyeom.run.dto.RunConditionUpdateRequest;
import com.team4.hanbbyeom.run.exception.RunMatchConditionNotFoundException;
import com.team4.hanbbyeom.run.repository.RunMatchConditionRepository;
import com.team4.hanbbyeom.run.repository.RunningCourseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 러닝 조건 관련 비즈니스 로직을 처리하는 서비스 클래스
// 매칭 요청 소유권 검증, 입력 데이터 범위 검증(거리, 페이스), 엔티티 저장 등 핵심 로직을 담당
@Service
@RequiredArgsConstructor
public class RunConditionService {
    // 허용되는 거리 상수 정의 (단위: 미터)
    private static final int MIN_DISTANCE_METERS = 1000; // 최소 허용 거리: 1km (1000m)
    private static final int MAX_DISTANCE_METERS = 20000; // 최대 허용 거리: 20km (20000m)
    // 허용되는 페이스 상수 정의 (단위: 초)
    private static final int MIN_PACE_SEC = 300; // 최소 허용 페이스: 5'00"/km (300초)
    private static final int MAX_PACE_SEC = 450; // 최대 허용 페이스: 7'30"/km (450초)

    private final RunMatchConditionRepository runMatchConditionRepository;
    private final RunningCourseRepository runningCourseRepository;
    private final MatchRequestRepository matchRequestRepository;

    @Transactional // 메서드 내부 작업을 하나의 트랜잭션으로 묶어 쓰기 작업 수행
    public Long create(Long currentUserId, RunConditionCreateRequest request) {

        // 매칭 요청(MatchRequest) 존재 여부 검증
        MatchRequest matchRequest = matchRequestRepository.findById(request.matchRequestId())
                .orElseThrow(() -> new MatchRequestNotFoundException(request.matchRequestId()));
        // 소유권 검증: 요청을 등록하려는 사용자가 매칭 요청서의 소유자인지 확인
        if (!matchRequest.isOwnedBy(currentUserId)) {
            throw new AccessDeniedException("본인 소유의 매칭 요청에만 조건을 등록할 수 있어요.");
        }
        // 선택한 코스(RunningCourse) 존재 여부 검증
        RunningCourse runningCourse = runningCourseRepository.findById(request.courseId())
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 코스예요"));
        // 거리 범위 검증 (1,000m ~ 20,000m 및 min <= max 여부)
        validateRange(request.distanceMinMeters(), request.distanceMaxMeters(),
                MIN_DISTANCE_METERS, MAX_DISTANCE_METERS, "거리");
        // 페이스 범위 검증 (300초 ~ 450초 및 min <= max 여부)
        validateRange(request.paceMinSec(), request.paceMaxSec(),
                MIN_PACE_SEC, MAX_PACE_SEC, "페이스");
        // 엔티티 생성 (빌더 패턴 활용)
        RunMatchCondition condition = RunMatchCondition.builder()
                .matchRequest(matchRequest)
                .runningCourse(runningCourse)
                .meetingPoint(request.meetingPoint())
                .distanceMinMeters(request.distanceMinMeters())
                .distanceMaxMeters(request.distanceMaxMeters())
                .paceMinSec(request.paceMinSec())
                .paceMaxSec(request.paceMaxSec())
                .build();
        // DB 저장, 생성된 조건의 매칭 요청 ID 반환
        return runMatchConditionRepository.save(condition).getMatchRequestId();
    }

    // 조건 조회 및 소유권 검증 공통 메서드 (조회/수정 시 내부 재사용)
    // currentUserId  현재 로그인한 사용자 ID
    // matchRequestId 조회할 매칭 요청 ID
    // @return 검증이 완료된 RunMatchCondition 엔티티
    private RunMatchCondition getOwnedCondition(Long currentUserId, Long matchRequestId) {
        // 러닝 조건 존재 여부 검증 (없을 경우 예외 발생)
        RunMatchCondition condition = runMatchConditionRepository.findById(matchRequestId)
                .orElseThrow(() -> new RunMatchConditionNotFoundException(matchRequestId));
        // 소유권 검증: 현재 사용자가 해당 조건의 매칭 요청 소유자인지 확인
        if (!condition.getMatchRequest().isOwnedBy(currentUserId)) {
            throw new AccessDeniedException("본인 소유의 매칭 요청만 접근할 수 있어요.");
        }
        return condition;
    }


    @Transactional(readOnly = true)
    public RunConditionResponse getById(Long currentUserId, Long matchRequestId) {
        // 조건 데이터 조회 및 본인 소유 권한 검증
        RunMatchCondition condition = getOwnedCondition(currentUserId, matchRequestId);
        // 검증 완료된 엔티티를 DTO로 변환하여 반환
        return RunConditionResponse.from(condition);
    }

    @Transactional
    public void update(Long currentUserId, Long matchRequestId, RunConditionUpdateRequest request) {
        // 기존 조건 데이터 조회 및 본인 소유 권한 검증
        RunMatchCondition condition = getOwnedCondition(currentUserId, matchRequestId);
        // 상태 검증: MatchRequestCommandService.update()(일정/대화수준 수정)와 동일한 규칙 —
        // 이미 신청이 들어와 PENDING_CONFIRMATION/MATCHED가 된 뒤에는 신청자가 본 조건과
        // 달라지면 안 되므로, 모집 중(SEARCHING)일 때만 코스/거리/페이스/만나는 곳 수정을 허용한다.
        if (condition.getMatchRequest().getStatus() != MatchRequestStatus.SEARCHING) {
            throw new IllegalStateException("모집 중인 게시글만 조건을 수정할 수 있어요.");
        }
        // 변경하려는 코스(RunningCourse) 존재 여부 검증
        RunningCourse runningCourse = runningCourseRepository.findById(request.courseId())
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 코스예요"));
        // 변경할 거리 및 페이스 범위 유효성 검증
        validateRange(request.distanceMinMeters(), request.distanceMaxMeters(), MIN_DISTANCE_METERS, MAX_DISTANCE_METERS, "거리");
        validateRange(request.paceMinSec(), request.paceMaxSec(), MIN_PACE_SEC, MAX_PACE_SEC, "페이스");
        // 엔티티 도메인 메서드(changeCondition)를 호출하여 상태 변경 (JPA Dirty Checking에 의해 자동 update 쿼리 실행)
        condition.changeCondition(
                runningCourse,
                request.meetingPoint(),
                request.distanceMinMeters(),
                request.distanceMaxMeters(),
                request.paceMinSec(),
                request.paceMaxSec());
    }

    private void validateRange(Integer min, Integer max, int allowedMin, int allowedMax, String label) {
        // 최소/최댓값이 시스템 허용 범위를 벗어나는지 확인
        if (min < allowedMin || max > allowedMax) {
            throw new IllegalArgumentException(label + "는 " + allowedMin + "~" + allowedMax + " 범위 안이어야 해요.");
        }
        // 최솟값이 최댓값보다 크게 들어왔는지 확인
        if (min > max) {
            throw new IllegalArgumentException(label + " 최소값이 최대값보다 클 수 없어요.");
        }
    }
}
