package com.team4.hanbbyeom.matching.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

// 모집 탭 목록(GET /api/matching/board)의 커서 페이징 응답 (이슈 #103).
// 프론트는 items를 그리고, hasNext가 true면 nextCursor를 다음 요청의 cursor로 넘겨 이어 받는다.
public record MatchBoardPageResponse(
        @Schema(description = "이번 페이지의 모집글. 최신 글부터(created_at DESC, id DESC)")
        List<MatchBoardItemResponse> items,
        @Schema(description = "다음 페이지 요청에 넘길 cursor(이번 페이지 마지막 글의 id). 다음 페이지가 없으면 null",
                example = "42", nullable = true)
        Long nextCursor,
        @Schema(description = "다음 페이지가 있는지", example = "true")
        boolean hasNext
) {
    // size+1건을 조회한 결과로 페이지를 만든다 — size보다 많이 왔으면 다음 페이지가 있다는 뜻이고,
    // 응답에는 size건까지만 담는다. nextCursor는 잘라낸 뒤 마지막 항목의 id.
    public static MatchBoardPageResponse of(List<MatchBoardItemResponse> fetched, int size) {
        boolean hasNext = fetched.size() > size;
        List<MatchBoardItemResponse> items = hasNext ? fetched.subList(0, size) : fetched;
        Long nextCursor = hasNext ? items.get(items.size() - 1).id() : null;
        return new MatchBoardPageResponse(items, nextCursor, hasNext);
    }
}
