package com.team4.hanbbyeom.matching.controller;

import com.team4.hanbbyeom.global.security.CustomUserDetails;
import com.team4.hanbbyeom.matching.domain.ActivityMatch;
import com.team4.hanbbyeom.matching.domain.MatchParticipant;
import com.team4.hanbbyeom.matching.dto.ActivityMatchStatusResponse;
import com.team4.hanbbyeom.matching.dto.MatchConfirmResponse;
import com.team4.hanbbyeom.matching.dto.TrustProfileResponse;
import com.team4.hanbbyeom.matching.exception.NotMatchParticipantException;
import com.team4.hanbbyeom.matching.repository.ActivityMatchRepository;
import com.team4.hanbbyeom.matching.repository.MatchParticipantRepository;
import com.team4.hanbbyeom.matching.service.MatchDecisionService;
import com.team4.hanbbyeom.matching.service.TrustProfileLookupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Matching - Matches", description = "확정 매칭(activity_match) 관련 API")
// Swagger UI에서 Authorize로 입력한 Bearer 토큰을 이 API 호출에 사용 (SwaggerConfig에 정의)
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/matching/matches")
public class ActivityMatchController {

    private final ActivityMatchRepository activityMatchRepository;
    private final MatchParticipantRepository matchParticipantRepository;
    private final TrustProfileLookupService trustProfileLookupService;
    private final MatchDecisionService matchDecisionService;

    public ActivityMatchController(ActivityMatchRepository activityMatchRepository,
                                   MatchParticipantRepository matchParticipantRepository,
                                   TrustProfileLookupService trustProfileLookupService,
                                   MatchDecisionService matchDecisionService) {
        this.activityMatchRepository = activityMatchRepository;
        this.matchParticipantRepository = matchParticipantRepository;
        this.trustProfileLookupService = trustProfileLookupService;
        this.matchDecisionService = matchDecisionService;
    }

    // 신청자·호스트 둘 다 조회 가능한 매칭 건 상태 조회 — 특히 신청자가 "내 신청이 거절됐는지
    // (REJECTED) 응답 시간이 지나 만료됐는지(EXPIRED) 확정됐는지(CONFIRMED)"를 구분해서 알
    // 방법이 없었는데(호스트 게시글 상태만 보면 둘 다 그냥 SEARCHING으로 보임), 이 API로
    // activityMatchId 기준 정확한 상태를 직접 조회할 수 있게 한다.
    //
    // ⚠️ 이 경로(GET /api/matching/matches/{activityMatchId})는 채팅 이슈에서 계획 중인
    // "매칭 요약 조회" API와 같은 자리를 쓸 예정이다. 채팅 쪽에서 새 엔드포인트를 따로 만들지
    // 말고, 이 API의 응답(ActivityMatchStatusResponse)에 코스명·장소·시간 등 필요한 필드를
    // 확장하는 방향으로 계약을 맞출 것 — 팀 리뷰로 합의된 방향.
    @Operation(summary = "매칭 건 상세·상태 조회",
            description = "이 매칭의 호스트 또는 신청자 본인만 조회할 수 있습니다. 신청자가 폴링해서 " +
                    "PROPOSED(대기중)/CONFIRMED(확정)/REJECTED(거절됨)/EXPIRED(응답시간 초과) 중 " +
                    "무엇인지 구분하는 용도로 쓸 수 있습니다.")
    @GetMapping("/{activityMatchId}")
    public ActivityMatchStatusResponse getStatus(
            @PathVariable Long activityMatchId,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        ActivityMatch activityMatch = activityMatchRepository.findById(activityMatchId)
                .orElseThrow(() -> new NotMatchParticipantException("존재하지 않는 매칭이에요."));

        List<MatchParticipant> participants = matchParticipantRepository.findByActivityMatchId(activityMatchId);
        boolean isParticipant = participants.stream()
                .anyMatch(p -> p.getUserId().equals(principal.getUserId()));
        if (!isParticipant) {
            throw new NotMatchParticipantException("본인이 관련된 매칭만 조회할 수 있어요.");
        }

        return new ActivityMatchStatusResponse(
                activityMatch.getId(),
                activityMatch.getStatus().name(),
                activityMatch.getMeetingCode(),
                activityMatch.getConfirmedAt(),
                activityMatch.getClosedAt()
        );
    }

    // activityMatchId에 신청한 사람(slot B)의 신뢰도 프로필 조회 — 이 매칭의 호스트(slot A)
    // 본인만 조회 가능(제3자·신청자 본인도 차단). 아직 신청자가 없으면(slot B 없음) 예외.
    @Operation(summary = "신청자 신뢰도 프로필 조회",
            description = "이 매칭의 호스트 본인만 조회할 수 있습니다. 아직 신청자가 없으면 404가 반환됩니다.")
    @GetMapping("/{activityMatchId}/applicant-profile")
    public TrustProfileResponse getApplicantProfile(
            @PathVariable Long activityMatchId,
            // @AuthenticationPrincipal: JWT 인증 필터가 SecurityContext에 넣어둔 인증된 사용자 정보
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        // 아래 호스트 본인 확인에서 사용하므로 사용자 id를 먼저 꺼내둠
        Long currentUserId = principal.getUserId();

        List<MatchParticipant> participants = matchParticipantRepository.findByActivityMatchId(activityMatchId);

        MatchParticipant host = participants.stream()
                .filter(p -> "A".equals(p.getSlot()))
                .findFirst()
                .orElseThrow(() -> new NotMatchParticipantException("존재하지 않는 매칭이에요."));

        // 본인(호스트)이 아닌 사람이 신청자 프로필을 보려 하면 차단
        if (!host.getUserId().equals(currentUserId)) {
            throw new NotMatchParticipantException("이 매칭의 호스트만 신청자 프로필을 볼 수 있어요.");
        }

        MatchParticipant applicant = participants.stream()
                .filter(p -> "B".equals(p.getSlot()))
                .findFirst()
                .orElseThrow(() -> new NotMatchParticipantException("아직 신청자가 없어요."));

        return trustProfileLookupService.lookup(applicant.getUserId());
    }

    // 호스트 수락 — CONFIRMED 전이 + meeting_code 발급 + 양쪽 게시글 MATCHED(신청자는
    // 본인 게시글이 있을 때만). 호스트 본인만 호출 가능, PROPOSED 상태일 때만 성공.
    @Operation(summary = "매칭 수락",
            description = "호스트 본인만 호출할 수 있습니다. 이미 응답했거나 종료된 매칭이면 409가 반환됩니다.")
    @PostMapping("/{activityMatchId}/accept")
    public MatchConfirmResponse accept(
            @PathVariable Long activityMatchId,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        return matchDecisionService.accept(principal.getUserId(), activityMatchId);
    }

    // 호스트 거절 — REJECTED 전이 + 양쪽 게시글 SEARCHING 복귀(신청자는 있을 때만).
    @Operation(summary = "매칭 거절",
            description = "호스트 본인만 호출할 수 있습니다. 이미 응답했거나 종료된 매칭이면 409가 반환됩니다.")
    @PostMapping("/{activityMatchId}/reject")
    public ResponseEntity<Void> reject(
            @PathVariable Long activityMatchId,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        matchDecisionService.reject(principal.getUserId(), activityMatchId);
        return ResponseEntity.noContent().build();
    }
}