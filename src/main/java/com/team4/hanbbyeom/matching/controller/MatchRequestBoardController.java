package com.team4.hanbbyeom.matching.controller;

import com.team4.hanbbyeom.global.security.CustomUserDetails;
import com.team4.hanbbyeom.matching.domain.MatchRequest;
import com.team4.hanbbyeom.matching.dto.MatchApplyRequest;
import com.team4.hanbbyeom.matching.dto.BoardSort;
import com.team4.hanbbyeom.matching.dto.MatchBoardPageResponse;
import com.team4.hanbbyeom.matching.dto.MyApplicationResponse;
import com.team4.hanbbyeom.trust.dto.TrustProfileResponse;
import com.team4.hanbbyeom.matching.exception.MatchRequestNotFoundException;
import com.team4.hanbbyeom.matching.exception.NotMatchParticipantException;
import com.team4.hanbbyeom.matching.repository.MatchRequestRepository;
import com.team4.hanbbyeom.matching.service.MatchApplyService;
import com.team4.hanbbyeom.matching.service.MatchRequestBoardService;
import com.team4.hanbbyeom.trust.service.TrustProfileLookupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@Tag(name = "Matching - Board", description = "모집 탭 목록 조회 및 신청/신청취소 API")
// Swagger UI에서 Authorize로 입력한 Bearer 토큰을 이 API 호출에 사용 (SwaggerConfig에 정의)
@SecurityRequirement(name = "bearerAuth")
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
    // 커서 페이징(이슈 #103): 첫 페이지는 cursor 없이, 다음 페이지는 직전 응답의 nextCursor를 cursor로.
    // 정렬(이슈 #123): sort=LATEST|SCHEDULED|DISTANCE, 기본 LATEST. size 범위(1~50)·sort 값 검증은 Service/BoardSort가 한다.
    @Operation(summary = "모집 탭 목록 조회 (커서 페이징·정렬)",
            description = "course/talkLevel은 정확히 일치하는 값만, minDistance~maxPace는 게시글의 범위와 겹치는 것만, " +
                    "datePreset(TODAY/TOMORROW/WEEKEND)은 해당 기간에 속하는 것만 필터링합니다. " +
                    "모든 필터는 선택값이며, 조회자 본인 글과 작성자가 다른 신청·활동 중인 글은 항상 제외됩니다. " +
                    "sort: LATEST(최신 등록순, 기본) / SCHEDULED(활동 날짜 빠른 순) / DISTANCE(최소 거리 짧은 순). " +
                    "size(기본 20, 최대 50)건씩 내려주며, 응답의 hasNext가 true면 nextCursor를 cursor로 넘겨 다음 페이지를 받습니다. " +
                    "cursor는 같은 sort·필터에서만 유효하므로 sort나 필터를 바꾸면 cursor 없이 첫 페이지부터 다시 요청하세요. " +
                    "size가 1~50 범위 밖이거나 sort가 허용 값이 아니면 400, 존재하지 않는 cursor는 빈 페이지(hasNext=false).")
    @GetMapping
    public MatchBoardPageResponse getBoard(
            @RequestParam(required = false) String course,
            @RequestParam(required = false) String talkLevel,
            @RequestParam(required = false) Integer minDistance,
            @RequestParam(required = false) Integer maxDistance,
            @RequestParam(required = false) Integer minPace,
            @RequestParam(required = false) Integer maxPace,
            @RequestParam(required = false) String datePreset, // "TODAY" | "TOMORROW" | "WEEKEND"
            @RequestParam(required = false) Long cursor,      // 직전 페이지 마지막 글의 id (첫 페이지면 생략)
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "LATEST") String sort, // LATEST | SCHEDULED | DISTANCE
            // @AuthenticationPrincipal: JWT 인증 필터가 SecurityContext에 넣어둔 인증된 사용자 정보
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        return matchRequestBoardService.getBoard(
                course, talkLevel, minDistance, maxDistance, minPace, maxPace, datePreset, principal.getUserId(),
                cursor, size, BoardSort.from(sort)
        );
    }

    // requestId(호스트 게시글)의 작성자 신뢰도 프로필 조회 — 신청하기 전 "이 사람 어떤 사람이지"
    // 미리보기 용도라, 응답 자체엔 인가 제한이 없다(로그인한 사용자면 누구나 조회 가능).
    @Operation(summary = "호스트 신뢰도 프로필 조회",
            description = "신청 전 미리보기 용도입니다. 로그인한 사용자면 누구나 조회할 수 있습니다.")
    @GetMapping("/{requestId}/host-profile")
    public TrustProfileResponse getHostProfile(@PathVariable Long requestId) {
        // 로그인한 사용자면 누구나 조회 가능 (신청 전 미리보기 용도)
        MatchRequest hostRequest = matchRequestRepository.findById(requestId)
                .orElseThrow(() -> new MatchRequestNotFoundException(requestId));

        return trustProfileLookupService.lookup(hostRequest.getUserId());
    }

    // 신청자 본인이 지금까지 넣은 신청 내역 전체 조회 — 화면(26 "내가 신청한 모집")의
    // 전체/대기 중/수락됨/거절됨/취소함 탭을 status 파라미터로 지원한다. status를 생략하면
    // 전체가 나온다. 값은 activity_match.status 원본이 아니라 화면 탭에 맞춘
    // PENDING/ACCEPTED/REJECTED/CANCELLED 4가지다(MyApplicationResponse 주석 참고).
    @Operation(summary = "내 신청 내역 조회",
            description = "본인이 지금까지 넣은 신청 내역을 최신순으로 조회합니다. status로 " +
                    "PENDING(대기 중)/ACCEPTED(수락됨)/REJECTED(거절됨)/CANCELLED(취소함) 중 하나를 " +
                    "지정하면 그 상태만 걸러서 보여주고, 생략하면 전체를 보여줍니다.")
    @GetMapping("/applications")
    public List<MyApplicationResponse> getMyApplications(
            @RequestParam(required = false) String status,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        return matchApplyService.getMyApplications(principal.getUserId(), status);
    }

    // requestId(호스트 게시글)에 신청 — 요청 바디(MatchApplyRequest)는 필드가 없는 빈 값이라
    // 신청 시점에 따로 입력받는 데이터는 없다. 성공하면 Location 헤더에 새로 생성된
    // activityMatchId 경로가 담긴다(201 Created).
    @Operation(summary = "모집글 신청",
            description = "요청 바디는 필요 없습니다(신청자는 본인 게시글 없이도 신청할 수 있습니다). " +
                    "성공 시 생성된 activity_match id로의 경로가 Location 헤더에 담깁니다. " +
                    "실패 응답: 400 — 본인 게시글이거나 활동 시작이 너무 임박해 신청할 수 없음, 404 — 존재하지 않는 모집글, " +
                    "409 — 이미 마감되었거나 신청이 진행 중인 모집글(작성자가 이미 다른 신청·활동 중인 경우 포함), " +
                    "또는 내가 이미 진행 중인 신청·활동이 있음. 목록에 보이는 글도 이 응답으로 신청이 거절될 수 있으므로 " +
                    "메시지를 그대로 안내한 뒤 목록을 새로고침하세요.")
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
    @Operation(summary = "신청 취소",
            description = "경로의 requestId는 activityMatchId가 아니라 호스트 게시글 id입니다. " +
                    "아직 호스트가 응답하지 않은(PROPOSED) 신청만 취소할 수 있습니다.")
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
