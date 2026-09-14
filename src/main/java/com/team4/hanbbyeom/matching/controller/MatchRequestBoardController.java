package com.team4.hanbbyeom.matching.controller;

import com.team4.hanbbyeom.matching.domain.MatchRequest;
import com.team4.hanbbyeom.matching.dto.MatchApplyRequest;
import com.team4.hanbbyeom.matching.dto.MatchBoardItemResponse;
import com.team4.hanbbyeom.matching.dto.TrustProfileResponse;
import com.team4.hanbbyeom.matching.exception.MatchRequestNotFoundException;
import com.team4.hanbbyeom.matching.exception.NotMatchParticipantException;
import com.team4.hanbbyeom.matching.repository.MatchRequestRepository;
import com.team4.hanbbyeom.matching.service.MatchApplyService;
import com.team4.hanbbyeom.matching.service.MatchRequestBoardService;
import com.team4.hanbbyeom.matching.service.TrustProfileLookupService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/matching/board")
public class MatchRequestBoardController {

    private final MatchRequestBoardService matchRequestBoardService;
    private final MatchRequestRepository matchRequestRepository;
    private final TrustProfileLookupService trustProfileLookupService;
    private final MatchApplyService matchApplyService;

    public MatchRequestBoardController(MatchRequestBoardService matchRequestBoardService, MatchRequestRepository matchRequestRepository, TrustProfileLookupService trustProfileLookupService, MatchApplyService matchApplyService) {
        this.matchRequestBoardService = matchRequestBoardService;
        this.matchRequestRepository = matchRequestRepository;
        this.trustProfileLookupService = trustProfileLookupService;
        this.matchApplyService = matchApplyService;
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

    @PostMapping("/{requestId}/apply")
    public ResponseEntity<Void> apply(
            @PathVariable Long requestId,
            @RequestBody(required = false) MatchApplyRequest request,
            @RequestHeader("X-USER-ID") Long currentUserId
    ) {
        Long activityMatchId = matchApplyService.apply(currentUserId, requestId);
        return ResponseEntity.created(URI.create("/api/matching/matches/" + activityMatchId)).build();
    }

    @PostMapping("/{requestId}/apply/cancel")
    public ResponseEntity<Void> cancelApplication(
            @PathVariable Long requestId,
            @RequestHeader("X-USER-ID") Long currentUserId
    ) {
        // 프론트는 requestId(호스트 게시글 id)만 알고 있으면 되고, activityMatchId를 찾는 건
        // MatchApplyService.cancelApplication() 내부에서 처리합니다. 컨트롤러는 그대로 위임만 합니다.
        matchApplyService.cancelApplication(currentUserId, requestId);
        return ResponseEntity.noContent().build();
    }
}
