package com.team4.hanbbyeom.matching.service;

import com.team4.hanbbyeom.matching.domain.ActivityMatch;
import com.team4.hanbbyeom.matching.domain.ActivityMatchStatus;
import com.team4.hanbbyeom.matching.domain.MatchRequest;
import com.team4.hanbbyeom.matching.domain.MatchRequestStatus;
import com.team4.hanbbyeom.matching.dto.MatchBoardItemResponse;
import com.team4.hanbbyeom.matching.dto.MatchRequestResponse;
import com.team4.hanbbyeom.matching.dto.MyPostResponse;
import com.team4.hanbbyeom.matching.dto.PendingApplicationResponse;
import com.team4.hanbbyeom.matching.exception.MatchRequestNotFoundException;
import com.team4.hanbbyeom.matching.exception.PendingApplicationNotFoundException;
import com.team4.hanbbyeom.matching.repository.ActivityMatchRepository;
import com.team4.hanbbyeom.matching.repository.MatchParticipantRepository;
import com.team4.hanbbyeom.matching.repository.MatchRequestRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class MatchRequestBoardService {

    // getMyActiveRequest()에서 쓰는 "활성" 상태 집합 — uq_match_request_active_user
    // 부분 유니크 인덱스와 동일하게 맞춰야 한다.
    private static final List<MatchRequestStatus> ACTIVE_STATUSES =
            List.of(MatchRequestStatus.SEARCHING, MatchRequestStatus.PENDING_CONFIRMATION, MatchRequestStatus.MATCHED);

    private final MatchRequestRepository matchRequestRepository;
    private final MatchParticipantRepository matchParticipantRepository;
    private final ActivityMatchRepository activityMatchRepository;
    private final JdbcTemplate jdbcTemplate;
    // "오늘/내일/이번 주말" 날짜 프리셋 계산은 TimeConfig의 Clock 빈으로만 한다 (테스트에서 시계 고정 가능, #94)
    private final Clock clock;

    public MatchRequestBoardService(MatchRequestRepository matchRequestRepository,
                                    MatchParticipantRepository matchParticipantRepository,
                                    ActivityMatchRepository activityMatchRepository,
                                    JdbcTemplate jdbcTemplate,
                                    Clock clock) {
        this.matchRequestRepository = matchRequestRepository;
        this.matchParticipantRepository = matchParticipantRepository;
        this.activityMatchRepository = activityMatchRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    // 모집 탭 목록 조회 — 읽기 전용이라 상태 전이는 없다. 필터는 전부 선택적(null 허용)이며,
    // 거리/페이스는 값이 정확히 일치하는 게 아니라 "게시글의 범위와 필터 범위가 겹치는지"로
    // 판단한다(MatchRequestRepository.searchBoard 참고). currentUserId로 본인 글은 항상 제외된다.
    public List<MatchBoardItemResponse> getBoard(String region, String talkLevel,
                                                 Integer minDistance, Integer maxDistance,
                                                 Integer minPace, Integer maxPace,
                                                 String datePreset, Long currentUserId) {

        OffsetDateTime[] range = resolveDateRange(datePreset);

        List<MatchRequestRepository.MatchBoardRow> rows = matchRequestRepository.searchBoard(
                region, talkLevel, minDistance, maxDistance, minPace, maxPace, range[0], range[1], currentUserId
        );

        Map<Long, String> nicknameByUserId = fetchNicknames(rows.stream().map(MatchRequestRepository.MatchBoardRow::getUserId).toList());

        return rows.stream()
                .map(row -> new MatchBoardItemResponse(
                        row.getId(),
                        row.getCourseName(),
                        row.getDistanceMinMeters(),
                        row.getDistanceMaxMeters(),
                        row.getTalkLevel(),
                        row.getScheduledAt().atOffset(ZoneOffset.UTC),
                        row.getPaceMinSec(),
                        row.getPaceMaxSec(),
                        new MatchBoardItemResponse.AuthorSummary(
                                row.getAuthorNickname(),
                                row.getAuthorRating(),
                                row.getAuthorCompletedCount()
                        )
                ))
                .collect(Collectors.toList());
    }

    // "오늘"/"내일"/"이번 주말" 같은 프리셋을 실제 날짜 범위로 변환. WEEKEND는 이번 주 토요일이
    // 이미 지났으면 다음 주 토요일로 넘어가므로, 목요일 밤에 조회해도 "이번 주말"이 항상 미래를
    // 가리킨다.
    private OffsetDateTime[] resolveDateRange(String datePreset) {
        if (datePreset == null) {
            return new OffsetDateTime[]{null, null};
        }
        ZoneId zone = ZoneId.of("Asia/Seoul");
        // clock은 UTC 기준이라(TimeConfig 참고) 서울 기준 "오늘"은 instant에 zone을 입혀서 구한다.
        // clock.withZone(zone) 대신 instant()만 쓰는 이유: 테스트에서 Clock을 Mockito 목으로 바꾸고
        // instant()/getZone()만 스텁하는 패턴(ChatMessageIntegrationTest)에서 withZone()은 null을 돌려준다.
        LocalDate today = LocalDate.ofInstant(clock.instant(), zone);

        LocalDate from;
        LocalDate to;
        switch (datePreset) {
            case "TODAY" -> { from = today; to = today.plusDays(1); }
            case "TOMORROW" -> { from = today.plusDays(1); to = today.plusDays(2); }
            case "WEEKEND" -> {
                LocalDate saturday = today.with(DayOfWeek.SATURDAY);
                if (saturday.isBefore(today)) saturday = saturday.plusWeeks(1);
                from = saturday;
                to = saturday.plusDays(2); // 토요일 00:00 ~ 월요일 00:00 (토+일 포함)
            }
            default -> throw new IllegalArgumentException("알 수 없는 datePreset: " + datePreset);
        }
        return new OffsetDateTime[]{
                from.atStartOfDay(zone).toOffsetDateTime(),
                to.atStartOfDay(zone).toOffsetDateTime()
        };
    }

    // users 테이블에서 닉네임만 가볍게 조회 (도메인 간 결합도를 낮추는 원칙, Day1과 동일)
    private Map<Long, String> fetchNicknames(List<Long> userIds) {
        if (userIds.isEmpty()) return Map.of();
        String inClause = userIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        return jdbcTemplate.query(
                "SELECT id, nickname FROM users WHERE id IN (" + inClause + ")",
                rs -> {
                    Map<Long, String> map = new java.util.HashMap<>();
                    while (rs.next()) {
                        map.put(rs.getLong("id"), rs.getString("nickname"));
                    }
                    return map;
                }
        );
    }

    // 모집글 상세 조회 — 목록(getBoard)과 달리 meetingPoint까지 포함하고, 조회자가 작성자
    // 본인인지(isOwner)와 응답 대기 중인 신청자 수(pendingApplicantCount)를 같이 내려줘서
    // 프론트가 "호스트 전용 화면 요소"를 보여줄지 판단할 수 있게 한다. 읽기 전용, 상태 전이 없음.
    public MatchRequestResponse getDetail(Long matchRequestId, Long currentUserId) {
        MatchRequestRepository.MatchRequestDetailRow row = matchRequestRepository.findDetailById(matchRequestId)
                .orElseThrow(() -> new MatchRequestNotFoundException(matchRequestId));

        long pendingApplicantCount = matchRequestRepository.countPendingApplicants(matchRequestId);
        String nickname = fetchNicknames(List.of(row.getUserId())).get(row.getUserId());

        return new MatchRequestResponse(
                row.getId(),
                row.getCourseName(),
                row.getDistanceMinMeters(),
                row.getDistanceMaxMeters(),
                row.getPaceMinSec(),
                row.getPaceMaxSec(),
                row.getMeetingPoint(),
                row.getScheduledAt().atOffset(ZoneOffset.UTC),
                row.getTalkLevel(),
                row.getStatus(),
                row.getUserId().equals(currentUserId),
                pendingApplicantCount,
                new MatchBoardItemResponse.AuthorSummary(nickname, null, null)
        );
    }

    // 내가 지금 갖고 있는 활성 모집글/신청 1건 조회(GET /api/matching/requests/me) — Postman/QA
    // 등에서 방금 등록한 게시글의 id를 몰라도(Location 헤더를 놓쳤어도) 확인할 수 있게 하고,
    // 프론트도 "지금 내가 모집 중인 글이 있는지"를 id 없이 바로 물어볼 수 있게 한다.
    public MatchRequestResponse getMyActiveRequest(Long userId) {
        MatchRequest matchRequest = matchRequestRepository.findByUserIdAndStatusIn(userId, ACTIVE_STATUSES)
                .orElseThrow(() -> new MatchRequestNotFoundException("현재 진행 중인 모집글이 없어요."));
        return getDetail(matchRequest.getId(), userId);
    }

    // 호스트 본인 게시글(matchRequestId)에 온 대기 중인 신청 1건 조회
    // applicant-profile, accept/reject API와 이어서 쓰기 위해 activityMatchId만 최소한으로 내려준다.
    public PendingApplicationResponse getPendingApplication(Long hostUserId, Long matchRequestId) {
        // 1) 게시글 존재 여부 + 본인 소유 검증
        MatchRequest matchRequest = matchRequestRepository.findById(matchRequestId)
                .orElseThrow(() -> new MatchRequestNotFoundException(matchRequestId));
        if (!matchRequest.isOwnedBy(hostUserId)) {
            throw new AccessDeniedException("본인 게시글의 신청만 조회할 수 있어요.");
        }

        // 2) 활성 참여 행에서 activityMatchId 역조회 (MatchApplyService.cancelApplication()과 동일한 방식)
        //    "신청이 없음"은 권한 문제가 아니라 리소스 부재이므로 404 전용 예외를 쓴다.
        Long activityMatchId = matchParticipantRepository.findActiveActivityMatchIdByMatchRequestId(matchRequestId)
                .orElseThrow(() -> new PendingApplicationNotFoundException("대기 중인 신청이 없어요."));

        // 3) CONFIRMED(확정)는 아직 활동이 안 끝난 "활성 상태"라 released_at을 의도적으로
        //    안 채우므로(MatchDecisionService.accept() 참고), 위 조회에서 이미 확정된 매칭도
        //    걸릴 수 있다. 그래서 상태까지 재확인한다 — PROPOSED일 때만 진짜 "대기 중"이다.
        ActivityMatch activityMatch = activityMatchRepository.findById(activityMatchId)
                .orElseThrow(() -> new PendingApplicationNotFoundException("대기 중인 신청이 없어요."));
        if (activityMatch.getStatus() != ActivityMatchStatus.PROPOSED) {
            throw new PendingApplicationNotFoundException("대기 중인 신청이 없어요.");
        }

        return new PendingApplicationResponse(activityMatchId, activityMatch.getDecisionExpiresAt());
    }

    // 내가 등록한 모집글 전체 목록 조회(GET /api/matching/requests) — 상태 필터링/재매핑은
    // 프론트가 담당하므로, 여기서는 최신순 전체 목록을 그대로 반환한다.
    public List<MyPostResponse> getMyPosts(Long userId) {
        return matchRequestRepository.findMyPosts(userId).stream()
                .map(row -> new MyPostResponse(
                        row.getId(),
                        row.getCourseName(),
                        row.getDistanceMinMeters(),
                        row.getDistanceMaxMeters(),
                        row.getScheduledAt().atOffset(ZoneOffset.UTC),
                        row.getTalkLevel(),
                        row.getStatus()
                ))
                .toList();
    }
}