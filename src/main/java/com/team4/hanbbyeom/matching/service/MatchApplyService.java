package com.team4.hanbbyeom.matching.service;

import com.team4.hanbbyeom.matching.domain.*;
import com.team4.hanbbyeom.matching.dto.MatchBoardItemResponse;
import com.team4.hanbbyeom.matching.dto.MyApplicationResponse;
import com.team4.hanbbyeom.matching.exception.*;
import com.team4.hanbbyeom.matching.repository.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class MatchApplyService {

    private static final long DECISION_WINDOW_HOURS = 24; // 호스트 응답 대기 기한(최대치 — 실제로는 활동 시작 1시간 전을 넘지 않도록 아래 apply()에서 제한)
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

    // 호스트 게시글에 신청을 넣는 기능. 성공하면 activity_match를 PROPOSED로 새로 만들고,
    // 호스트 게시글만 PENDING_CONFIRMATION으로 전이한다(신청자는 본인 게시글이 없어도 신청할
    // 수 있으므로, 있더라도 그 게시글 상태는 건드리지 않는다).
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

        // 탈퇴한 호스트는 영원히 수락·거절할 수 없어 신청자만 응답 기한까지 묶인다.
        // 게시판 목록 필터(searchBoard)는 발견만 막을 뿐, 캐시된 id로 들어오는 직접 신청과
        // 호스트가 PENDING_CONFIRMATION에서 탈퇴한 뒤 expireOverdue()가 게시글을 SEARCHING으로
        // 되돌리는 경로는 막지 못한다 — 그래서 신청 시점에도 확인한다.
        // 탈퇴 여부가 드러나지 않도록 위 상태 검사와 같은 메시지를 사용한다.
        // (users는 matching 도메인이 직접 참조하지 않으므로 fetchNicknames()처럼 jdbcTemplate으로 조회)
        Boolean hostWithdrawn = jdbcTemplate.queryForObject(
                "SELECT deleted_at IS NOT NULL FROM users WHERE id = ?",
                Boolean.class, hostRequest.getUserId()
        );
        if (Boolean.TRUE.equals(hostWithdrawn)) {
            throw new MatchRequestNotSearchingException("이미 마감되었거나 신청이 진행 중인 모집글이에요.");
        }

        // 3) 호스트의 run_match_condition + running_course 조회 (코스명/거리/페이스/만나는 곳)
        Map<String, Object> condition = jdbcTemplate.queryForMap(
                """
                SELECT rc.meeting_point, rc.distance_min_meters, rc.distance_max_meters,
                       rc.pace_min_sec, rc.pace_max_sec,
                       co.name AS course_name, co.route_description
                FROM run_match_condition rc
                JOIN running_course co ON co.id = rc.course_id
                WHERE rc.match_request_id = ?
                """,
                hostRequestId
        );

        Integer distanceMinMeters = (Integer) condition.get("distance_min_meters");
        Integer distanceMaxMeters = (Integer) condition.get("distance_max_meters");
        Integer paceMinSec = (Integer) condition.get("pace_min_sec");
        Integer paceMaxSec = (Integer) condition.get("pace_max_sec");
        String meetingPoint = (String) condition.get("meeting_point");
        String courseName = (String) condition.get("course_name");
        String routeDescription = (String) condition.get("route_description");

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime scheduledAt = hostRequest.getScheduledAt();
        // scheduled_end_at은 프론트에 노출되지 않는 내부용 컬럼이라 정밀 계산 없이 고정 버퍼로 처리
        OffsetDateTime scheduledEndAt = scheduledAt.plusHours(ACTIVITY_DURATION_HOURS);

        // 호스트 응답 기한은 "최대 24시간, 단 활동 시작 1시간 전을 넘지 않도록" 제한한다.
        // 예전엔 무조건 now+24시간으로 고정해서, 등록 최소 리드타임(3시간)보다 짧게 남은
        // 일정(예: 오늘 저녁 신청)은 등록은 되지만 신청은 거절되는 모순이 있었다(팀원 리뷰로
        // 발견). "등록 최소 리드타임을 24시간보다 늘린다"는 대안도 있었지만, 그러면 당일
        // 매칭("오늘 저녁 같이 뛸 사람 구하기")이라는 핵심 시나리오 자체가 막혀버려서
        // 기각했다(2차 리뷰 피드백). 대신 임박한 일정일수록 호스트가 더 빨리 답해야 한다는
        // 쪽으로 응답 기한 자체를 유동적으로 줄인다 — 등록 리드타임 정책은 건드리지 않는다.
        OffsetDateTime maxDecisionExpiresAt = now.plusHours(DECISION_WINDOW_HOURS);
        OffsetDateTime latestPossibleDecisionExpiresAt = scheduledAt.minusHours(1);
        OffsetDateTime decisionExpiresAt = maxDecisionExpiresAt.isBefore(latestPossibleDecisionExpiresAt)
                ? maxDecisionExpiresAt
                : latestPossibleDecisionExpiresAt;

        // 활동 시작까지 1시간도 안 남아서 decisionExpiresAt이 이미 지금(now)보다 이전이면
        // 호스트가 응답할 시간 자체가 없으므로 신청을 막는다.
        if (!decisionExpiresAt.isAfter(now)) {
            throw new InvalidMatchRequestException("활동 시작 시각이 너무 임박해서 신청할 수 없어요.");
        }

        // 4) activity_match 생성 (PROPOSED) — createdAt에 위 가드에서 쓴 것과 정확히 같은 now를
        // 넘긴다. ActivityMatch가 내부에서 OffsetDateTime.now()를 다시 호출하면 그 사이 시간차만큼
        // created_at < decision_expires_at 제약을 위반할 여지가 생긴다(팀원 리뷰로 발견한 회귀 —
        // ActivityMatch 생성자 주석 참고).
        ActivityMatch activityMatch = new ActivityMatch(
                scheduledAt, scheduledEndAt, hostRequest.getTalkLevel(),
                meetingPoint, courseName, distanceMinMeters, distanceMaxMeters, routeDescription,
                paceMinSec, paceMaxSec, decisionExpiresAt, now
        );
        Long activityMatchId = activityMatchRepository.save(activityMatch).getId();

        // 5) match_participant 2건 생성 — 호스트(A, PENDING), 신청자(B, ACCEPTED)
        // 신청자는 본인 게시글 없이도 신청할 수 있으므로 matchRequestId를 null로 저장한다
        // (match_participant.match_request_id는 V11에서 nullable로 변경됨).
        matchParticipantRepository.save(new MatchParticipant(
                activityMatchId, hostRequestId, hostRequest.getUserId(), "A", AcceptStatus.PENDING
        ));
        matchParticipantRepository.save(new MatchParticipant(
                activityMatchId, null, applicantUserId, "B", AcceptStatus.ACCEPTED
        ));

        // 6) 호스트 게시글만 상태 전이 (신청자는 본인 게시글이 없을 수 있어 건드리지 않음)
        hostRequest.changeStatus(MatchRequestStatus.PENDING_CONFIRMATION);

        return activityMatchId;
    }

    // 신청자가 "내가 넣은 신청"을 스스로 철회하는 기능. 호스트가 거절하는 것과 결과(REJECTED)는
    // 같지만, 주체가 신청자 본인이고 진입점(컨트롤러 경로)이 다르다는 점만 다르다.
    // ⚠️ 이슈 #23 원문엔 없지만 디자인(14d 화면) 흐름상 필요해서 추가한 기능입니다.
    @Transactional
    public void cancelApplication(Long applicantUserId, Long matchRequestId) {
        // 1) apply()와 동일하게 matching_mutex를 먼저 잠근다. 취소 처리 중에 다른 신청/취소
        //    트랜잭션이 같은 activity_match를 동시에 건드리지 못하게 순서를 직렬화한다.
        jdbcTemplate.queryForObject("SELECT id FROM matching_mutex WHERE id = 1 FOR UPDATE", Long.class);

        // 2) 컨트롤러는 (호스트) 게시글 matchRequestId만 알고 있으므로, 현재 활성 상태인
        //    activityMatchId를 match_participant에서 역으로 찾는다 — matchRequestId와
        //    activityMatchId는 서로 다른 시퀀스라 그대로 넘겨 쓰면 안 됨.
        Long activityMatchId = matchParticipantRepository.findActiveActivityMatchIdByMatchRequestId(matchRequestId)
                .orElseThrow(() -> new ActivityMatchNotFoundException("존재하지 않는 매칭이에요."));

        // 3) 위에서 찾은 activityMatchId로 실제 ActivityMatch 엔티티를 가져온다.
        ActivityMatch activityMatch = activityMatchRepository.findById(activityMatchId)
                .orElseThrow(() -> new ActivityMatchNotFoundException("존재하지 않는 매칭이에요."));

        // 4) 이 매칭에 연결된 참가자 2명(호스트 slot A, 신청자 slot B)을 모두 가져온 뒤,
        //    - 신청자(slot B) 중 요청자 본인과 일치하는 행을 찾는다. 없으면 "내 신청이 아닌 매칭을
        //      취소하려는 시도"이므로 차단 — 프론트가 취소 버튼을 숨겨도 API를 직접 호출할 수
        //      있으므로 서버가 반드시 다시 검증해야 한다.
        //    - 호스트(slot A) 행도 같이 찾아둔다(6번에서 호스트 게시글 상태 복구에 필요).
        var participants = matchParticipantRepository.findByActivityMatchId(activityMatchId);
        MatchParticipant applicant = participants.stream()
                .filter(p -> "B".equals(p.getSlot()) && p.getUserId().equals(applicantUserId))
                .findFirst()
                .orElseThrow(() -> new NotMatchParticipantException("본인이 신청한 매칭만 취소할 수 있어요."));
        MatchParticipant host = participants.stream()
                .filter(p -> "A".equals(p.getSlot()))
                .findFirst()
                .orElseThrow(() -> new NotMatchParticipantException("존재하지 않는 매칭이에요."));

        // 5) 이미 호스트가 수락/거절했거나(CONFIRMED/REJECTED), 자동 만료·종료된 매칭은
        //    취소할 대상이 아니므로 PROPOSED(응답 대기 중) 상태일 때만 취소를 허용한다.
        if (activityMatch.getStatus() != ActivityMatchStatus.PROPOSED) {
            throw new MatchRequestNotSearchingException("이미 확정되었거나 종료된 매칭은 취소할 수 없어요.");
        }

        // 6) activity_match를 REJECTED로 전이시킨다 — 별도의 CANCELLED 처리를 새로 만들지 않고
        //    호스트 거절과 같은 reject()를 재사용한다("제안이 무산됨"이라는 결과는 동일하므로).
        activityMatch.reject(applicantUserId); // REJECTED 재사용 — 신청자 취소도 "제안이 무산됨"이라는 점은 동일

        // 6-1) 매칭이 무산됐으므로 두 참여 연결 모두 해제 — 안 하면 released_at이 계속 null로
        //      남아서 uq_participant_active_user 부분 유니크 인덱스에 걸려 이 두 사람이 다시는
        //      매칭에 참여할 수 없게 된다.
        host.release();
        applicant.release();

        // 7) 호스트 게시글은 다시 모집 중(SEARCHING)으로 되돌려서 다른 사람이 신청할 수 있게 한다.
        MatchRequest hostRequest = matchRequestRepository.findById(host.getMatchRequestId())
                .orElseThrow(() -> new MatchRequestNotFoundException(host.getMatchRequestId()));
        hostRequest.changeStatus(MatchRequestStatus.SEARCHING);

        // 8) 신청자는 본인 게시글 없이 신청했을 수 있음(matchRequestId가 null) — 있을 때만 되돌린다.
        Long applicantMatchRequestId = applicant.getMatchRequestId();
        if (applicantMatchRequestId != null) {
            MatchRequest applicantRequest = matchRequestRepository.findById(applicantMatchRequestId)
                    .orElseThrow(() -> new MatchRequestNotFoundException(applicantMatchRequestId));
            applicantRequest.changeStatus(MatchRequestStatus.SEARCHING);
        }
    }

    // getMyApplications()의 status 파라미터로 허용되는 값 — MyApplicationResponse.status()가
    // 실제로 내려주는 값과 정확히 같은 집합이어야 한다.
    private static final Set<String> VALID_APPLICATION_STATUSES = Set.of("PENDING", "ACCEPTED", "REJECTED", "CANCELLED");

    // 신청자 본인이 지금까지 넣은 신청 내역 전체 조회(GET /api/matching/board/applications).
    // statusFilter가 null이면 전체, 아니면 PENDING/ACCEPTED/REJECTED/CANCELLED 중 하나로 걸러
    // 화면(26)의 탭(전체/대기 중/수락됨/거절됨/취소함)을 그대로 지원한다. 읽기 전용이라 락을
    // 잡지 않는다 — apply()/cancelApplication()과 달리 matching_mutex와 무관.
    //
    // status에 오타 등 알 수 없는 값이 오면 조용히 빈 배열을 내려주는 대신 400으로 막는다 —
    // 그렇지 않으면 "신청 내역이 없음"과 "필터 값이 잘못됨"이 응답만으로 구분이 안 돼서
    // 프론트/QA가 디버깅하기 어렵다(팀원 리뷰로 발견).
    public List<MyApplicationResponse> getMyApplications(Long applicantUserId, String statusFilter) {
        if (statusFilter != null && !VALID_APPLICATION_STATUSES.contains(statusFilter)) {
            throw new InvalidMatchRequestException(
                    "status는 PENDING/ACCEPTED/REJECTED/CANCELLED 중 하나여야 해요."
            );
        }

        return activityMatchRepository.findMyApplications(applicantUserId).stream()
                .map(row -> toMyApplicationResponse(row, applicantUserId))
                .filter(response -> statusFilter == null || statusFilter.equals(response.status()))
                .toList();
    }

    private MyApplicationResponse toMyApplicationResponse(ActivityMatchRepository.MyApplicationRow row,
                                                           Long applicantUserId) {
        return new MyApplicationResponse(
                row.getId(),
                row.getHostMatchRequestId(),
                toDisplayStatus(row.getStatus(), row.getClosedByUserId(), applicantUserId),
                row.getCourseName(),
                row.getDistanceMinMeters(),
                row.getDistanceMaxMeters(),
                row.getScheduledAt().atOffset(ZoneOffset.UTC),
                row.getTalkLevel(),
                new MatchBoardItemResponse.AuthorSummary(row.getHostNickname(), row.getHostRating(), row.getHostCompletedCount())
        );
    }

    // activity_match.status를 화면 탭에 맞춘 4가지 표시 상태로 재매핑 — MyApplicationResponse
    // 클래스 주석에 각 케이스의 판단 기준을 적어뒀다.
    private String toDisplayStatus(String rawStatus, Long closedByUserId, Long applicantUserId) {
        return switch (ActivityMatchStatus.valueOf(rawStatus)) {
            case PROPOSED -> "PENDING";
            case CONFIRMED, ENDED -> "ACCEPTED";
            case REJECTED -> applicantUserId.equals(closedByUserId) ? "CANCELLED" : "REJECTED";
            case EXPIRED -> "REJECTED";
            // CANCELLED는 확정 후 취소된 매칭(현재는 참가자 회원 탈퇴 시 시스템이 처리, closedByUserId=null)이다.
            // "취소함" 탭은 신청자가 직접 취소한 건만 담으므로(MyApplicationResponse 주석), 신청자 본인이
            // 닫은 경우만 CANCELLED로 두고 그 외는 "내 신청이 성사되지 않음"인 REJECTED로 묶는다.
            case CANCELLED -> applicantUserId.equals(closedByUserId) ? "CANCELLED" : "REJECTED";
        };
    }
}
