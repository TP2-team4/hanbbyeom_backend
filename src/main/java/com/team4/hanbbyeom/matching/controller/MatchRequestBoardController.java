package com.team4.hanbbyeom.matching.controller;

import com.team4.hanbbyeom.matching.domain.MatchRequest;
import com.team4.hanbbyeom.matching.dto.MatchBoardItemResponse;
import com.team4.hanbbyeom.matching.dto.TrustProfileResponse;
import com.team4.hanbbyeom.matching.exception.MatchRequestNotFoundException;
import com.team4.hanbbyeom.matching.repository.MatchRequestRepository;
import com.team4.hanbbyeom.matching.service.MatchRequestBoardService;
import com.team4.hanbbyeom.matching.service.TrustProfileLookupService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/matching/board")
public class MatchRequestBoardController {

    private final MatchRequestBoardService matchRequestBoardService;
    private final MatchRequestRepository matchRequestRepository;
    private final TrustProfileLookupService trustProfileLookupService;

    public MatchRequestBoardController(MatchRequestBoardService matchRequestBoardService, MatchRequestRepository matchRequestRepository, TrustProfileLookupService trustProfileLookupService) {
        this.matchRequestBoardService = matchRequestBoardService;
        this.matchRequestRepository = matchRequestRepository;
        this.trustProfileLookupService = trustProfileLookupService;
    }

    @GetMapping
    public List<MatchBoardItemResponse> getBoard(
            @RequestParam(required = false) String course,
            @RequestParam(required = false) String talkLevel,
            @RequestParam(required = false) Integer minDistance,
            @RequestParam(required = false) Integer maxDistance,
            @RequestParam(required = false) String datePreset, // "TODAY" | "TOMORROW" | "WEEKEND"
            @RequestHeader("X-USER-ID") Long currentUserId // TODO: 담당 A 인증 방식으로 교체
    ) {
        return matchRequestBoardService.getBoard(course, talkLevel, minDistance, maxDistance, datePreset, currentUserId);
    }

    @GetMapping("/{requestId}/host-profile")
    public TrustProfileResponse getHostProfile(@PathVariable Long requestId) {
        // 로그인한 사용자면 누구나 조회 가능 (신청 전 미리보기 용도)
        MatchRequest hostRequest = matchRequestRepository.findById(requestId)
                .orElseThrow(() -> new MatchRequestNotFoundException(requestId));

        return trustProfileLookupService.lookup(hostRequest.getUserId());
    }
}
