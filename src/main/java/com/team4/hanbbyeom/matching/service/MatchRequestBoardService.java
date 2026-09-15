package com.team4.hanbbyeom.matching.service;

import com.team4.hanbbyeom.matching.dto.MatchBoardItemResponse;
import com.team4.hanbbyeom.matching.dto.MatchRequestResponse;
import com.team4.hanbbyeom.matching.exception.MatchRequestNotFoundException;
import com.team4.hanbbyeom.matching.repository.MatchRequestRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class MatchRequestBoardService {

    private final MatchRequestRepository matchRequestRepository;
    private final JdbcTemplate jdbcTemplate;

    public MatchRequestBoardService(MatchRequestRepository matchRequestRepository, JdbcTemplate jdbcTemplate) {
        this.matchRequestRepository = matchRequestRepository;
        this.jdbcTemplate = jdbcTemplate;
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
        LocalDate today = LocalDate.now(zone);

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
}