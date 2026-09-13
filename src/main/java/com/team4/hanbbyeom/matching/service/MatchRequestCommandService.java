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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Service
public class MatchRequestCommandService {

    // V3 SQL의 chk_match_request_time 제약: created_at < search_expires_at < scheduled_at
    // 이 두 상수 관계가 깨지면(MIN_LEAD_HOURS <= SEARCH_WINDOW_HOURS) DB 제약을 절대 통과 못 하니 주의하세요.
    private static final long MIN_LEAD_HOURS = 3;       // "지금부터 최소 3시간 뒤"
    private static final long SEARCH_WINDOW_HOURS = 1;  // "시작 1시간 전에 모집 마감"

    private final MatchRequestRepository matchRequestRepository;

    public MatchRequestCommandService(MatchRequestRepository matchRequestRepository) {
        this.matchRequestRepository = matchRequestRepository;
    }

    @Transactional
    public Long create(Long userId, MatchRequestCreateRequest request) {
        validateScheduledAt(request.scheduledAt());
        OffsetDateTime searchExpiresAt = request.scheduledAt().minusHours(SEARCH_WINDOW_HOURS);

        MatchRequest matchRequest = new MatchRequest(
                userId,
                request.runMatchConditionId(),
                request.scheduledAt(),
                TalkLevel.valueOf(request.talkLevel()),
                searchExpiresAt
        );

        try {
            return matchRequestRepository.save(matchRequest).getId();
        } catch (DataIntegrityViolationException e) {
            // uq_match_request_active_user 위반: 이미 활성 게시글/신청이 있는 사용자
            throw new AlreadyHasActiveMatchRequestException("이미 진행 중인 모집글 또는 신청이 있어요.");
        }
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