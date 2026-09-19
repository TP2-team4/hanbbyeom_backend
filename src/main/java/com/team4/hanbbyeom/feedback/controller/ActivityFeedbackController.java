package com.team4.hanbbyeom.feedback.controller;

import com.team4.hanbbyeom.feedback.dto.FeedbackStatusResponse;
import com.team4.hanbbyeom.feedback.dto.NoShowReportCreateRequest;
import com.team4.hanbbyeom.feedback.dto.ReviewCreateRequest;
import com.team4.hanbbyeom.feedback.service.ActivityFeedbackService;
import com.team4.hanbbyeom.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

// ActivityMatchController와 같은 /api/matching/matches 경로를 쓰지만, 소유 도메인은
// feedback이다 — URL 계약(매칭 건 하나에 딸린 부가 기능)은 matching 쪽에 맞추고,
// 실제 로직/데이터는 feedback 도메인이 담당하는 구조 (팀 컨벤션: TrustProfileLookupService를
// 여러 컨트롤러가 주입받아 쓰는 것과 같은 방식).
@Tag(name = "Feedback", description = "활동 후기 작성 / 노쇼 신고 / 제출 가능 여부 조회 API")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/matching/matches")
public class ActivityFeedbackController {

    private final ActivityFeedbackService activityFeedbackService;

    public ActivityFeedbackController(ActivityFeedbackService activityFeedbackService) {
        this.activityFeedbackService = activityFeedbackService;
    }

    // 활동이 끝난 뒤 "후기 작성 필요"/"이미 제출함" 등을 프론트가 미리 확인할 때 쓰는 API.
    @Operation(summary = "후기/신고 제출 가능 여부 조회",
            description = "이 매칭의 참가자 본인만 조회할 수 있습니다. 아직 활동이 끝나지 않았거나 " +
                    "이미 후기/신고를 제출했다면 canSubmit이 false로 내려갑니다.")
    @GetMapping("/{activityMatchId}/feedback-status")
    public FeedbackStatusResponse getFeedbackStatus(
            @PathVariable Long activityMatchId,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        return activityFeedbackService.getFeedbackStatus(activityMatchId, principal.getUserId());
    }

    // 활동 후기 등록. 응답 바디가 필요 없어서(등록 결과를 다시 조회할 필요가 없음)
    // ActivityMatchController.reject()와 동일하게 204 No Content로 응답한다.
    @Operation(summary = "활동 후기 작성",
            description = "활동이 종료된 매칭의 참가자 본인만 호출할 수 있습니다. " +
                    "이미 후기/신고를 제출했으면 409, 아직 활동이 안 끝났으면 400이 반환됩니다.")
    @PostMapping("/{activityMatchId}/review")
    public ResponseEntity<Void> submitReview(
            @PathVariable Long activityMatchId,
            @Valid @RequestBody ReviewCreateRequest request,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        activityFeedbackService.submitReview(activityMatchId, principal.getUserId(), request);
        return ResponseEntity.noContent().build();
    }

    // 노쇼 신고 접수 — 신고는 접수 후 취소/수정 기능이 없으므로 응답도 등록 확인용 204뿐이다.
    @Operation(summary = "노쇼 신고 접수",
            description = "활동이 종료된 매칭의 참가자 본인만 호출할 수 있습니다. " +
                    "이미 후기/신고를 제출했으면 409, 아직 활동이 안 끝났으면 400이 반환됩니다.")
    @PostMapping("/{activityMatchId}/no-show-report")
    public ResponseEntity<Void> submitNoShowReport(
            @PathVariable Long activityMatchId,
            @Valid @RequestBody NoShowReportCreateRequest request,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        activityFeedbackService.submitNoShowReport(activityMatchId, principal.getUserId(), request);
        return ResponseEntity.noContent().build();
    }
}