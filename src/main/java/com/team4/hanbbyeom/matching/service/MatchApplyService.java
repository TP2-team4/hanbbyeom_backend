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

@Service
public class MatchApplyService {

    // 호스트 응답 대기 기한. MatchRequestCommandService.MIN_LEAD_HOURS가 이 값보다 커야
    // "등록은 됐는데 아무도 신청할 수 없는 글"이 생기지 않으므로(팀원 리뷰로 발견), 패키지
    // 밖에서 못 보게 private으로 감싸지 않고 그대로 참조해서 두 값이 다시 어긋나지 않게 한다.
    static final long DECISION_WINDOW_HOURS = 24;
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

        OffsetDateTime scheduledAt = hostRequest.getScheduledAt();
        // scheduled_end_at은 프론트에 노출되지 않는 내부용 컬럼이라 정밀 계산 없이 고정 버퍼로 처리
        OffsetDateTime scheduledEndAt = scheduledAt.plusHours(ACTIVITY_DURATION_HOURS);
        OffsetDateTime decisionExpiresAt = OffsetDateTime.now().plusHours(DECISION_WINDOW_HOURS);

        // decisionExpiresAt이 scheduledAt보다 늦으면 chk_activity_match_time 위반 → 사전 검증
        if (!decisionExpiresAt.isBefore(scheduledAt)) {
            throw new InvalidMatchRequestException("활동 시작 시각이 너무 임박해서 신청할 수 없어요.");
        }

        // 4) activity_match 생성 (PROPOSED)
        ActivityMatch activityMatch = new ActivityMatch(
                scheduledAt, scheduledEndAt, hostRequest.getTalkLevel(),
                meetingPoint, courseName, distanceMinMeters, distanceMaxMeters, routeDescription,
                paceMinSec, paceMaxSec, decisionExpiresAt
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

    // 신청자 본인이 지금까지 넣은 신청 내역 전체 조회(GET /api/matching/board/applications).
    // statusFilter가 null이면 전체, 아니면 PENDING/ACCEPTED/REJECTED/CANCELLED 중 하나로 걸러
    // 화면(26)의 탭(전체/대기 중/수락됨/거절됨/취소함)을 그대로 지원한다. 읽기 전용이라 락을
    // 잡지 않는다 — apply()/cancelApplication()과 달리 matching_mutex와 무관.
    public List<MyApplicationResponse> getMyApplications(Long applicantUserId, String statusFilter) {
        return activityMatchRepository.findMyApplications(applicantUserId).stream()
                .map(row -> toMyApplicationResponse(row, applicantUserId))
                .filter(response -> statusFilter == null || statusFilter.equals(response.status()))
                .toList();
    }

    private MyApplicationResponse toMyApplicationResponse(ActivityMatchRepository.MyApplicationRow row,
                                                           Long applicantUserId) {
        return new MyApplicationResponse(
                row.getId(),
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
            // CANCELLED는 현재 어디서도 실제로 세팅하지 않는 상태다(cancelApplication()이
            // REJECTED를 재사용함 — 위 주석 참고). 그래도 enum 값이 존재하는 한 switch를
            // 완전하게 유지해야 하므로, 나중에 실제로 쓰이게 되더라도 자연스럽게 맞도록 매핑해둔다.
            case CANCELLED -> "CANCELLED";
        };
    }
}