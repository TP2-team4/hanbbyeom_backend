package com.team4.hanbbyeom.matching.service;

import com.team4.hanbbyeom.matching.dto.MatchBoardItemResponse;
import com.team4.hanbbyeom.matching.dto.MatchRequestDetailResponse;
import com.team4.hanbbyeom.matching.exception.MatchRequestNotFoundException;
import com.team4.hanbbyeom.matching.repository.MatchRequestRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.DayOfWeek;
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

    public List<MatchBoardItemResponse> getBoard(String region, String talkLevel,
                                                 Integer minDistance, Integer maxDistance,
                                                 String datePreset, Long currentUserId) {

        OffsetDateTime[] range = resolveDateRange(datePreset);

        List<MatchRequestRepository.MatchBoardRow> rows = matchRequestRepository.searchBoard(
                region, talkLevel, minDistance, maxDistance, range[0], range[1], currentUserId
        );

        Map<Long, String> nicknameByUserId = fetchNicknames(rows.stream().map(MatchRequestRepository.MatchBoardRow::getUserId).toList());

        return rows.stream()
                .map(row -> new MatchBoardItemResponse(
                        row.getId(),
                        row.getCourseName(),
                        row.getDistanceMeters(),
                        row.getTalkLevel(),
                        row.getScheduledAt(),
                        row.getPaceMinSec(),
                        row.getPaceMaxSec(),
                        new MatchBoardItemResponse.AuthorSummary(
                                nicknameByUserId.get(row.getUserId()),
                                null,   // TODO: 신뢰 프로필 연동
                                null    // TODO: 신뢰 프로필 연동
                        )
                ))
                .collect(Collectors.toList());
    }

    // "오늘"/"내일"/"이번 주말" 같은 프리셋을 실제 날짜 범위로 변환
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

    // users 테이블에서 닉네임만 가볍게 조회 (매칭 도메인이 users를 직접 조인하지 않고 이렇게 분리 조회하는 이유는
    // Day1에서 정한 "도메인 간 결합도를 낮춘다"는 원칙과 같습니다)
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

    public MatchRequestDetailResponse getDetail(Long matchRequestId, Long currentUserId) {
        MatchRequestRepository.MatchRequestDetailRow row = matchRequestRepository.findDetailById(matchRequestId)
                .orElseThrow(() -> new MatchRequestNotFoundException(matchRequestId));

        long pendingApplicantCount = matchRequestRepository.countPendingApplicants(matchRequestId);
        String nickname = fetchNicknames(List.of(row.getUserId())).get(row.getUserId());

        return new MatchRequestDetailResponse(
                row.getId(),
                row.getCourseName(),
                row.getDistanceMeters(),
                row.getPaceMinSec(),
                row.getPaceMaxSec(),
                row.getMeetingPoint(),
                row.getScheduledAt(),
                row.getTalkLevel(),
                row.getStatus(),
                row.getUserId().equals(currentUserId),
                pendingApplicantCount,
                new MatchBoardItemResponse.AuthorSummary(nickname, null, null)
        );
    }
}