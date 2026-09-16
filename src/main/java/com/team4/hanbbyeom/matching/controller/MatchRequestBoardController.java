package com.team4.hanbbyeom.matching.controller;

import com.team4.hanbbyeom.global.security.CustomUserDetails;
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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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

    // 모집 탭 목록 조회 — 쿼리 파라미터는 전부 선택값(생략 가능): course/talkLevel은 정확히
    // 일치하는 값만, minDistance~maxPace는 "게시글의 범위와 겹치는지"로 필터링, datePreset은
    // TODAY/TOMORROW/WEEKEND 중 하나. 인증된 사용자 id로 본인이 올린 글은 결과에서 항상 제외된다.
    @GetMapping
    public List<MatchBoardItemResponse> getBoard(
            @RequestParam(required = false) String course,
            @RequestParam(required = false) String talkLevel,
            @RequestParam(required = false) Integer minDistance,
            @RequestParam(required = false) Integer maxDistance,
            @RequestParam(required = false) Integer minPace,
            @RequestParam(required = false) Integer maxPace,
            @RequestParam(required = false) String datePreset, // "TODAY" | "TOMORROW" | "WEEKEND"
            // @AuthenticationPrincipal: JWT 인증 필터가 SecurityContext에 넣어둔 인증된 사용자 정보
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        return matchRequestBoardService.getBoard(course, talkLevel, minDistance, maxDistance, minPace, maxPace, datePreset, principal.getUserId());
    }

    // requestId(호스트 게시글)의 작성자 신뢰도 프로필 조회 — 신청하기 전 "이 사람 어떤 사람이지"
    // 미리보기 용도라, 응답 자체엔 인가 제한이 없다(로그인한 사용자면 누구나 조회 가능).
    @GetMapping("/{requestId}/host-profile")
    public TrustProfileResponse getHostProfile(@PathVariable Long requestId) {
        // 로그인한 사용자면 누구나 조회 가능 (신청 전 미리보기 용도)
        MatchRequest hostRequest = matchRequestRepository.findById(requestId)
                .orElseThrow(() -> new MatchRequestNotFoundException(requestId));

        return trustProfileLookupService.lookup(hostRequest.getUserId());
    }

    // requestId(호스트 게시글)에 신청 — 요청 바디(MatchApplyRequest)는 필드가 없는 빈 값이라
    // 신청 시점에 따로 입력받는 데이터는 없다. 성공하면 Location 헤더에 새로 생성된
    // activityMatchId 경로가 담긴다(201 Created).
    @PostMapping("/{requestId}/apply")
    public ResponseEntity<Void> apply(
            @PathVariable Long requestId,
            @RequestBody(required = false) MatchApplyRequest request,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        Long activityMatchId = matchApplyService.apply(principal.getUserId(), requestId);
        return ResponseEntity.created(URI.create("/api/matching/matches/" + activityMatchId)).build();
    }

    // 신청 취소 — 경로의 requestId는 activityMatchId가 아니라 호스트 게시글 id다(프론트는
    // 이것만 알면 됨). 요청/응답 바디 없음.
    @PostMapping("/{requestId}/apply/cancel")
    public ResponseEntity<Void> cancelApplication(
            @PathVariable Long requestId,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        // 프론트는 requestId(호스트 게시글 id)만 알고 있으면 되고, activityMatchId를 찾는 건
        // MatchApplyService.cancelApplication() 내부에서 처리합니다. 컨트롤러는 그대로 위임만 합니다.
        matchApplyService.cancelApplication(principal.getUserId(), requestId);
        return ResponseEntity.noContent().build();
    }
}
