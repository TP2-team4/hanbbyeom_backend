package com.team4.hanbbyeom.matching.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public record MatchBoardItemResponse(
        Long id,
        String courseName,
        Integer distanceMinMeters,
        Integer distanceMaxMeters,
        String talkLevel,
        OffsetDateTime scheduledAt,
        Integer paceMinSec,
        Integer paceMaxSec,
        AuthorSummary author
) {
    // 모집 목록·모집글 상세(author)·내 신청 내역(host)이 함께 쓰는 작성자 요약.
    // 상세와 내 신청 내역은 작성자가 탈퇴해도 조회되므로 nickname이 null일 수 있다(목록에서는 탈퇴한 작성자의 글을 제외).
    public record AuthorSummary(
            @Schema(description = "작성자 닉네임. 작성자가 탈퇴한 경우 null이며, 표시 문구는 클라이언트에서 정한다",
                    example = "한뼘러너", nullable = true)
            String nickname,
            @Schema(description = "받은 후기의 평균 별점. 후기가 하나도 없으면 null", example = "4.5", nullable = true)
            Double rating,
            // trust_profile 행이 없는 사용자(활동 이력 없음)도 횟수는 0으로 내려준다 — 프로필 조회
            // API(TrustProfileLookupService.lookup())와 같은 규칙. rating만 "평가 없음"이라 null (이슈 #94)
            @Schema(description = "완료한 활동 수. 활동 이력이 없으면 0", example = "12")
            Integer completedCount,
            @Schema(description = "받은 노쇼 신고 횟수. 없으면 0", example = "0")
            Integer noShowCount,
            @Schema(description = "가장 최근에 받은 후기 한 줄. 받은 후기가 하나도 없으면 null", nullable = true)
            LatestReview latestReview
    ) {
        // 노쇼 신고와 마찬가지로 신뢰 프로필 카드에 보여줄 "가장 최근 후기 한 줄" (이슈 #70)
        public record LatestReview(
                @Schema(description = "한 줄 후기. 별점만 남긴 후기면 null", example = "페이스 잘 맞춰주셨어요", nullable = true)
                String comment,
                @Schema(description = "후기 작성 시각")
                OffsetDateTime createdAt
        ) {
            // 조회 쿼리(LEFT JOIN LATERAL)의 결과를 LatestReview로 바꾼다. 세 화면(목록/상세/내 신청 내역)이
            // 같은 규칙을 써야 해서 여기에 한 번만 둔다.
            // "후기가 있는지"는 comment가 아니라 createdAt으로 판단한다 — 후기는 있는데 한 줄 후기만 안 쓴
            // 경우(별점만 등록)가 정상 케이스라, comment == null을 "후기 없음"으로 읽으면 그 후기가 통째로
            // 사라진다. createdAt은 후기 행이 있으면 항상 값이 있다(NOT NULL).
            public static LatestReview of(String comment, Instant createdAt) {
                if (createdAt == null) {
                    return null;
                }
                return new LatestReview(comment, createdAt.atOffset(ZoneOffset.UTC));
            }
        }
    }
}
