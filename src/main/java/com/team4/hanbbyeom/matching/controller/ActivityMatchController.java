package com.team4.hanbbyeom.matching.controller;

import com.team4.hanbbyeom.matching.domain.MatchParticipant;
import com.team4.hanbbyeom.matching.dto.MatchConfirmResponse;
import com.team4.hanbbyeom.matching.dto.TrustProfileResponse;
import com.team4.hanbbyeom.matching.exception.NotMatchParticipantException;
import com.team4.hanbbyeom.matching.repository.MatchParticipantRepository;
import com.team4.hanbbyeom.matching.service.MatchDecisionService;
import com.team4.hanbbyeom.matching.service.TrustProfileLookupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Matching - Matches", description = "확정 매칭(activity_match) 관련 API")
@RestController
@RequestMapping("/api/matching/matches")
public class ActivityMatchController {

    private final MatchParticipantRepository matchParticipantRepository;
    private final TrustProfileLookupService trustProfileLookupService;
    private final MatchDecisionService matchDecisionService;

    public ActivityMatchController(MatchParticipantRepository matchParticipantRepository,
                                   TrustProfileLookupService trustProfileLookupService, MatchDecisionService matchDecisionService) {
        this.matchParticipantRepository = matchParticipantRepository;
        this.trustProfileLookupService = trustProfileLookupService;
        this.matchDecisionService = matchDecisionService;
    }

    // activityMatchId에 신청한 사람(slot B)의 신뢰도 프로필 조회 — 이 매칭의 호스트(slot A)
    // 본인만 조회 가능(제3자·신청자 본인도 차단). 아직 신청자가 없으면(slot B 없음) 예외.
    @Operation(summary = "신청자 신뢰도 프로필 조회",
            description = "이 매칭의 호스트 본인만 조회할 수 있습니다. 아직 신청자가 없으면 404가 반환됩니다.")
    @GetMapping("/{activityMatchId}/applicant-profile")
    public TrustProfileResponse getApplicantProfile(
            @PathVariable Long activityMatchId,
            @RequestHeader("X-USER-ID") Long currentUserId // TODO: 담당 A 인증 방식으로 교체
    ) {
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
            @RequestHeader("X-USER-ID") Long currentUserId
    ) {
        return matchDecisionService.accept(currentUserId, activityMatchId);
    }

    // 호스트 거절 — REJECTED 전이 + 양쪽 게시글 SEARCHING 복귀(신청자는 있을 때만).
    @Operation(summary = "매칭 거절",
            description = "호스트 본인만 호출할 수 있습니다. 이미 응답했거나 종료된 매칭이면 409가 반환됩니다.")
    @PostMapping("/{activityMatchId}/reject")
    public ResponseEntity<Void> reject(
            @PathVariable Long activityMatchId,
            @RequestHeader("X-USER-ID") Long currentUserId
    ) {
        matchDecisionService.reject(currentUserId, activityMatchId);
        return ResponseEntity.noContent().build();
    }
}