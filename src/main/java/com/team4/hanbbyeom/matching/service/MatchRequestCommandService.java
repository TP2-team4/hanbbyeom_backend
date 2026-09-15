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

    // 모집글(match_request)을 SEARCHING 상태로 새로 만들고, 같은 트랜잭션 안에서 러닝 조건까지
    // 등록한다. 유니크 제약(uq_match_request_active_user) 위반은 "이미 활성 게시글/신청이 있음"
    // 예외로 변환해 사용자에게 원인을 알려준다.
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

    // 일정(scheduledAt)·대화 수준만 수정한다 — 코스/거리/페이스/만나는 곳은 RunConditionService
    // (팀원 B) 담당이라 여기서 다루지 않는다. 상태 전이는 없고(SEARCHING 유지), SEARCHING이
    // 아닌 글을 수정하려 하면 MatchRequest.changeConditions()가 IllegalStateException을 던진다.
    @Transactional
    public void update(Long userId, Long matchRequestId, MatchRequestUpdateRequest request) {
        MatchRequest matchRequest = getOwnedMatchRequest(userId, matchRequestId, "수정");
        validateScheduledAt(request.scheduledAt());
        OffsetDateTime searchExpiresAt = request.scheduledAt().minusHours(SEARCH_WINDOW_HOURS);
        matchRequest.changeConditions(request.scheduledAt(), TalkLevel.valueOf(request.talkLevel()), searchExpiresAt);
    }

    // 게시글을 CANCELLED로 전이시켜 모집 탭에서 내린다. 주의: 현재 상태를 검사하지 않으므로
    // 이미 신청이 걸려 PENDING_CONFIRMATION인 글도 그대로 취소 가능하다 — 이 경우 이미 생성된
    // activity_match/match_participant 쪽은 여기서 정리하지 않으니 별도 확인이 필요하다.
    @Transactional
    public void cancel(Long userId, Long matchRequestId) {
        MatchRequest matchRequest = getOwnedMatchRequest(userId, matchRequestId, "내리");
        matchRequest.changeStatus(MatchRequestStatus.CANCELLED);
    }

    // update()/cancel() 공용 — 게시글을 찾아 존재 여부(404)와 소유권(403)을 함께 검증한다.
    private MatchRequest getOwnedMatchRequest(Long userId, Long matchRequestId, String actionForMessage) {
        MatchRequest matchRequest = matchRequestRepository.findById(matchRequestId)
                .orElseThrow(() -> new MatchRequestNotFoundException(matchRequestId));
        if (!matchRequest.isOwnedBy(userId)) {
            throw new AccessDeniedException("본인 게시글만 " + actionForMessage + "할 수 있어요.");
        }
        return matchRequest;
    }

    // create()/update() 공용 — 활동 시작 시각이 너무 임박하면 (scheduledAt - 1시간으로 계산되는)
    // searchExpiresAt이 created_at보다 앞서버려 DB 제약(chk_match_request_time)을 위반하게 되므로,
    // 저장 전에 미리 걸러서 500 대신 400으로 응답한다.
    private void validateScheduledAt(OffsetDateTime scheduledAt) {
        OffsetDateTime minAllowed = OffsetDateTime.now().plusHours(MIN_LEAD_HOURS);
        if (scheduledAt.isBefore(minAllowed)) {
            throw new InvalidMatchRequestException(
                    "활동 시작 시각은 지금부터 최소 " + MIN_LEAD_HOURS + "시간 이후여야 해요."
            );
        }
    }
}