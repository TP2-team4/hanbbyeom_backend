package com.team4.hanbbyeom.matching.controller;

import com.team4.hanbbyeom.matching.domain.MatchParticipant;
import com.team4.hanbbyeom.matching.dto.TrustProfileResponse;
import com.team4.hanbbyeom.matching.exception.NotMatchParticipantException;
import com.team4.hanbbyeom.matching.repository.MatchParticipantRepository;
import com.team4.hanbbyeom.matching.service.TrustProfileLookupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Matching - Matches", description = "확정 매칭(activity_match) 관련 API")
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
}