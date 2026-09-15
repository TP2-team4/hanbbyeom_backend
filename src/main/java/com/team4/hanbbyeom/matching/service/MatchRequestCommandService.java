package com.team4.hanbbyeom.matching.service;

import com.team4.hanbbyeom.matching.domain.MatchRequest;
import com.team4.hanbbyeom.matching.domain.MatchRequestStatus;
import com.team4.hanbbyeom.matching.domain.TalkLevel;
import com.team4.hanbbyeom.matching.dto.MatchRequestCreateRequest;
import com.team4.hanbbyeom.matching.dto.MatchRequestUpdateRequest;
import com.team4.hanbbyeom.matching.exception.AlreadyHasActiveMatchRequestException;
import com.team4.hanbbyeom.matching.exception.InvalidMatchRequestException;
import com.team4.hanbbyeom.matching.exception.MatchRequestNotFoundException;
import com.team4.hanbbyeom.matching.repository.MatchRequestRepository;
import com.team4.hanbbyeom.run.dto.RunConditionCreateRequest;
import com.team4.hanbbyeom.run.service.RunConditionService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Service
public class MatchRequestCommandService {

    private static final long MIN_LEAD_HOURS = 3;
    private static final long SEARCH_WINDOW_HOURS = 1;

    private final MatchRequestRepository matchRequestRepository;
    private final RunConditionService runConditionService; // 새로 주입

    public MatchRequestCommandService(MatchRequestRepository matchRequestRepository,
                                      RunConditionService runConditionService) {
        this.matchRequestRepository = matchRequestRepository;
        this.runConditionService = runConditionService;
    }

    @Transactional
    public Long create(Long userId, MatchRequestCreateRequest request) {
        validateScheduledAt(request.scheduledAt());
        OffsetDateTime searchExpiresAt = request.scheduledAt().minusHours(SEARCH_WINDOW_HOURS);

        MatchRequest matchRequest = new MatchRequest(
                userId,
                request.scheduledAt(),
                TalkLevel.valueOf(request.talkLevel()),
                searchExpiresAt
        );

        Long matchRequestId;
        try {
            matchRequestId = matchRequestRepository.save(matchRequest).getId();
        } catch (DataIntegrityViolationException e) {
            throw new AlreadyHasActiveMatchRequestException("이미 진행 중인 모집글 또는 신청이 있어요.");
        }

        // 코스·거리·페이스·만나는 곳 4개는 B의 RunConditionService가 검증(범위/코스 존재 여부)과
        // 저장까지 전담. C는 더 이상 raw SQL로 직접 run_match_condition에 쓰지 않음.
        // matchRequest가 IDENTITY 전략이라 save() 시점에 이미 INSERT가 실행돼 있고,
        // 같은 트랜잭션(같은 영속성 컨텍스트)이라 아래 호출의 findById()가 바로 찾아냄 — flush 필요 없음.
        runConditionService.create(userId, new RunConditionCreateRequest(
                matchRequestId,
                request.courseId(),
                request.meetingPoint(),
                request.distanceMinMeters(),
                request.distanceMaxMeters(),
                request.paceMinSec(),
                request.paceMaxSec()
        ));

        return matchRequestId;
    }

    @Transactional
    public void update(Long userId, Long matchRequestId, MatchRequestUpdateRequest request) {
        MatchRequest matchRequest = getOwnedMatchRequest(userId, matchRequestId, "수정");
        validateScheduledAt(request.scheduledAt());
        OffsetDateTime searchExpiresAt = request.scheduledAt().minusHours(SEARCH_WINDOW_HOURS);
        matchRequest.changeConditions(request.scheduledAt(), TalkLevel.valueOf(request.talkLevel()), searchExpiresAt);
    }

    @Transactional
    public void cancel(Long userId, Long matchRequestId) {
        MatchRequest matchRequest = getOwnedMatchRequest(userId, matchRequestId, "내리");
        matchRequest.changeStatus(MatchRequestStatus.CANCELLED);
    }

    private MatchRequest getOwnedMatchRequest(Long userId, Long matchRequestId, String actionForMessage) {
        MatchRequest matchRequest = matchRequestRepository.findById(matchRequestId)
                .orElseThrow(() -> new MatchRequestNotFoundException(matchRequestId));
        if (!matchRequest.isOwnedBy(userId)) {
            throw new AccessDeniedException("본인 게시글만 " + actionForMessage + "할 수 있어요.");
        }
        return matchRequest;
    }

    private void validateScheduledAt(OffsetDateTime scheduledAt) {
        OffsetDateTime minAllowed = OffsetDateTime.now().plusHours(MIN_LEAD_HOURS);
        if (scheduledAt.isBefore(minAllowed)) {
            throw new InvalidMatchRequestException(
                    "활동 시작 시각은 지금부터 최소 " + MIN_LEAD_HOURS + "시간 이후여야 해요."
            );
        }
    }
}