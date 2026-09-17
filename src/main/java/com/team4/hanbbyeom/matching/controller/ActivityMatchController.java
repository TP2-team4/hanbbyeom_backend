package com.team4.hanbbyeom.matching.controller;

import com.team4.hanbbyeom.global.security.CustomUserDetails;
import com.team4.hanbbyeom.matching.domain.MatchParticipant;
import com.team4.hanbbyeom.matching.dto.TrustProfileResponse;
import com.team4.hanbbyeom.matching.exception.NotMatchParticipantException;
import com.team4.hanbbyeom.matching.repository.MatchParticipantRepository;
import com.team4.hanbbyeom.matching.service.TrustProfileLookupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Matching - Matches", description = "확정 매칭(activity_match) 관련 API")
// Swagger UI에서 Authorize로 입력한 Bearer 토큰을 이 API 호출에 사용 (SwaggerConfig에 정의)
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/matching/matches")
public class ActivityMatchController {

    private final MatchParticipantRepository matchParticipantRepository;
    private final TrustProfileLookupService trustProfileLookupService;

    public ActivityMatchController(MatchParticipantRepository matchParticipantRepository,
                                   TrustProfileLookupService trustProfileLookupService) {
        this.matchParticipantRepository = matchParticipantRepository;
        this.trustProfileLookupService = trustProfileLookupService;
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
}