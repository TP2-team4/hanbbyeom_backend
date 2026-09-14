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
import org.springframework.jdbc.core.JdbcTemplate;
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
    private final JdbcTemplate jdbcTemplate;

    public MatchRequestCommandService(MatchRequestRepository matchRequestRepository, JdbcTemplate jdbcTemplate) {
        this.matchRequestRepository = matchRequestRepository;
        this.jdbcTemplate = jdbcTemplate;
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
            // uq_match_request_active_user 위반: 이미 활성 게시글/신청이 있는 사용자
            throw new AlreadyHasActiveMatchRequestException("이미 진행 중인 모집글 또는 신청이 있어요.");
        }

        // match_request가 먼저 커밋 대기 상태로 존재해야 이 INSERT의 FK(match_request_id)가 통과함
        jdbcTemplate.update(
                """
                INSERT INTO run_match_condition
                    (match_request_id, course_id, meeting_point, distance_min_meters, distance_max_meters, pace_min_sec, pace_max_sec)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                matchRequestId, request.courseId(), request.meetingPoint(),
                request.distanceMinMeters(), request.distanceMaxMeters(),
                request.paceMinSec(), request.paceMaxSec()
        );

        return matchRequestId;
    }

    @Transactional
    public void update(Long userId, Long matchRequestId, MatchRequestUpdateRequest request) {
        MatchRequest matchRequest = getOwnedMatchRequest(userId, matchRequestId, "수정");
        validateScheduledAt(request.scheduledAt());
        OffsetDateTime searchExpiresAt = request.scheduledAt().minusHours(SEARCH_WINDOW_HOURS);

        // 1) 도메인 상태 검증을 먼저 — SEARCHING이 아니면 여기서 IllegalStateException이 터지고
        //    아래 run_match_condition UPDATE는 아예 실행되지 않음
        matchRequest.changeConditions(request.scheduledAt(), TalkLevel.valueOf(request.talkLevel()), searchExpiresAt);

        // 2) 검증을 통과한 요청만 run_match_condition에 반영
        jdbcTemplate.update(
                """
                UPDATE run_match_condition
                SET course_id = ?, meeting_point = ?, distance_min_meters = ?, distance_max_meters = ?,
                    pace_min_sec = ?, pace_max_sec = ?
                WHERE match_request_id = ?
                """,
                request.courseId(), request.meetingPoint(), request.distanceMinMeters(), request.distanceMaxMeters(),
                request.paceMinSec(), request.paceMaxSec(), matchRequestId
        );
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