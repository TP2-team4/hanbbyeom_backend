package com.team4.hanbbyeom.matching.dto;

import com.team4.hanbbyeom.global.exception.InvalidRequestValueException;
import java.util.Arrays;

// 모집 탭 목록(GET /api/matching/board)의 정렬 옵션 (이슈 #123).
// 커서 페이징은 정렬 키로 자르기 때문에, 정렬마다 쿼리(정렬 키 + 커서 비교 조건)가 따로 있다
// — MatchRequestRepository.searchBoardLatest/ByScheduled/ByDistance. sort를 바꾸면 cursor는 무효이므로
// 프론트는 첫 페이지부터 다시 요청해야 한다(필터를 바꿀 때와 같은 규칙).
public enum BoardSort {
    // 최신 등록순: created_at DESC, id DESC (기본값, #103에서 고정한 기존 동작)
    LATEST,
    // 활동 날짜 빠른 순: scheduled_at ASC, id ASC. SEARCHING 글은 기한이 지나면 #107이 만료시키므로 미래 활동만 남는다
    SCHEDULED,
    // 거리 짧은 순: distance_min_meters ASC, distance_max_meters ASC, id ASC — 글의 거리 범위 중 "최소 거리" 기준
    DISTANCE;

    // 요청 파라미터 문자열을 enum으로. Enum.valueOf()의 "No enum constant ..." 메시지 대신 사용자에게 보여줄 문구로
    // IllegalArgumentException을 던진다 → GlobalExceptionHandler가 400 (채팅 afterId, 페이징 size 검증과 같은 방식)
    public static BoardSort from(String value) {
        return Arrays.stream(values())
                .filter(sort -> sort.name().equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new InvalidRequestValueException(
                        "sort는 LATEST, SCHEDULED, DISTANCE 중 하나여야 합니다."));
    }
}
