package com.team4.hanbbyeom.matching.service;

import com.team4.hanbbyeom.matching.domain.*;
import com.team4.hanbbyeom.matching.exception.*;
import com.team4.hanbbyeom.matching.repository.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;

@Service
public class MatchApplyService {

    private static final long DECISION_WINDOW_HOURS = 24; // 호스트 응답 대기 기한
    private static final long ACTIVITY_DURATION_HOURS = 2; // scheduled_end_at 계산용 고정 버퍼 (프론트 미노출, 종료 배치 내부용)

    private final MatchRequestRepository matchRequestRepository;
    private final ActivityMatchRepository activityMatchRepository;
    private final MatchParticipantRepository matchParticipantRepository;
    private final JdbcTemplate jdbcTemplate;

    public MatchApplyService(MatchRequestRepository matchRequestRepository,
                             ActivityMatchRepository activityMatchRepository,
                             MatchParticipantRepository matchParticipantRepository,
                             JdbcTemplate jdbcTemplate) {
        this.matchRequestRepository = matchRequestRepository;
        this.activityMatchRepository = activityMatchRepository;
        this.matchParticipantRepository = matchParticipantRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public Long apply(Long applicantUserId, Long hostRequestId) {
        // 1) matching_mutex 행을 먼저 잠가서, 동시에 들어온 다른 신청 트랜잭션과 순서를 직렬화
        jdbcTemplate.queryForObject("SELECT id FROM matching_mutex WHERE id = 1 FOR UPDATE", Long.class);

        // 2) 호스트 게시글이 여전히 SEARCHING인지 재확인 (락을 잡은 뒤 다시 확인해야 경합 조건을 막을 수 있음)
        MatchRequest hostRequest = matchRequestRepository.findById(hostRequestId)
                .orElseThrow(() -> new MatchRequestNotFoundException(hostRequestId));

        if (hostRequest.getUserId().equals(applicantUserId)) {
            throw new InvalidMatchRequestException("본인 게시글에는 신청할 수 없어요.");
        }
        if (hostRequest.getStatus() != MatchRequestStatus.SEARCHING) {
            throw new MatchRequestNotSearchingException("이미 마감되었거나 신청이 진행 중인 모집글이에요.");
        }

        // 3) 신청자 본인의 활성(SEARCHING) 모집글이 있는지 확인 — match_participant의 FK 제약상 필수
        MatchRequest applicantRequest = matchRequestRepository
                .findByUserIdAndStatus(applicantUserId, MatchRequestStatus.SEARCHING)
                .orElseThrow(() -> new NoActiveMatchRequestException(
                        "먼저 본인의 모집 조건을 등록해야 다른 사람 글에 신청할 수 있어요."));

        // 4) 호스트의 run_match_condition + running_course 조회 (코스명/거리/페이스/만나는 곳)
        Map<String, Object> condition = jdbcTemplate.queryForMap(
                """
                SELECT rc.meeting_point, rc.distance_min_meters, rc.pace_min_sec, rc.pace_max_sec,
                       co.name AS course_name, co.route_description
                FROM run_match_condition rc
                JOIN running_course co ON co.id = rc.course_id
                WHERE rc.match_request_id = ?
                """,
                hostRequestId
        );

        Integer distanceMeters = (Integer) condition.get("distance_min_meters"); // 호스트의 하한값 사용 (확인 필요)
        Integer paceMinSec = (Integer) condition.get("pace_min_sec");
        Integer paceMaxSec = (Integer) condition.get("pace_max_sec");
        String meetingPoint = (String) condition.get("meeting_point");
        String courseName = (String) condition.get("course_name");
        String routeDescription = (String) condition.get("route_description");

        OffsetDateTime scheduledAt = hostRequest.getScheduledAt();
        // scheduled_end_at은 프론트에 노출되지 않는 내부용 컬럼이라 정밀 계산 없이 고정 버퍼로 처리
        OffsetDateTime scheduledEndAt = scheduledAt.plusHours(ACTIVITY_DURATION_HOURS);
        OffsetDateTime decisionExpiresAt = OffsetDateTime.now().plusHours(DECISION_WINDOW_HOURS);

        // decisionExpiresAt이 scheduledAt보다 늦으면 chk_activity_match_time 위반 → 사전 검증
        if (!decisionExpiresAt.isBefore(scheduledAt)) {
            throw new InvalidMatchRequestException("활동 시작 시각이 너무 임박해서 신청할 수 없어요.");
        }

        // 5) activity_match 생성 (PROPOSED)
        ActivityMatch activityMatch = new ActivityMatch(
                scheduledAt, scheduledEndAt, hostRequest.getTalkLevel(),
                meetingPoint, courseName, distanceMeters, routeDescription,
                paceMinSec, paceMaxSec, decisionExpiresAt
        );
        Long activityMatchId = activityMatchRepository.save(activityMatch).getId();

        // 6) match_participant 2건 생성 — 호스트(A, PENDING), 신청자(B, ACCEPTED)
        matchParticipantRepository.save(new MatchParticipant(
                activityMatchId, hostRequestId, hostRequest.getUserId(), "A", AcceptStatus.PENDING
        ));
        matchParticipantRepository.save(new MatchParticipant(
                activityMatchId, applicantRequest.getId(), applicantUserId, "B", AcceptStatus.ACCEPTED
        ));

        // 7) 양쪽 match_request 상태 전이 (호스트 + 신청자 둘 다)
        hostRequest.changeStatus(MatchRequestStatus.PENDING_CONFIRMATION);
        applicantRequest.changeStatus(MatchRequestStatus.PENDING_CONFIRMATION);

        return activityMatchId;
    }

    @Transactional
    public void cancelApplication(Long applicantUserId, Long activityMatchId) {
        // ⚠️ 이슈 #23 원문엔 없지만 디자인(14d 화면) 흐름상 필요해서 추가한 기능입니다.
        jdbcTemplate.queryForObject("SELECT id FROM matching_mutex WHERE id = 1 FOR UPDATE", Long.class);

        ActivityMatch activityMatch = activityMatchRepository.findById(activityMatchId)
                .orElseThrow(() -> new NotMatchParticipantException("존재하지 않는 매칭이에요."));

        var participants = matchParticipantRepository.findByActivityMatchId(activityMatchId);
        MatchParticipant applicant = participants.stream()
                .filter(p -> "B".equals(p.getSlot()) && p.getUserId().equals(applicantUserId))
                .findFirst()
                .orElseThrow(() -> new NotMatchParticipantException("본인이 신청한 매칭만 취소할 수 있어요."));
        MatchParticipant host = participants.stream()
                .filter(p -> "A".equals(p.getSlot()))
                .findFirst()
                .orElseThrow(() -> new NotMatchParticipantException("존재하지 않는 매칭이에요."));

        if (activityMatch.getStatus() != ActivityMatchStatus.PROPOSED) {
            throw new MatchRequestNotSearchingException("이미 확정되었거나 종료된 매칭은 취소할 수 없어요.");
        }

        activityMatch.reject(applicantUserId); // REJECTED 재사용 — 신청자 취소도 "제안이 무산됨"이라는 점은 동일

        MatchRequest hostRequest = matchRequestRepository.findById(host.getMatchRequestId())
                .orElseThrow(() -> new MatchRequestNotFoundException(host.getMatchRequestId()));
        MatchRequest applicantRequest = matchRequestRepository.findById(applicant.getMatchRequestId())
                .orElseThrow(() -> new MatchRequestNotFoundException(applicant.getMatchRequestId()));

        // 취소 전이라 두 게시글 모두 SEARCHING으로 되돌림
        hostRequest.changeStatus(MatchRequestStatus.SEARCHING);
        applicantRequest.changeStatus(MatchRequestStatus.SEARCHING);
    }
}