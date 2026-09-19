package com.team4.hanbbyeom.matching.controller;

import com.team4.hanbbyeom.global.security.CustomUserDetails;
import com.team4.hanbbyeom.matching.dto.*;
import com.team4.hanbbyeom.matching.service.MatchRequestBoardService;
import com.team4.hanbbyeom.matching.service.MatchRequestCommandService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@Tag(name = "Matching - Requests", description = "모집글 등록/조회/수정/취소 API")
// Swagger UI에서 Authorize로 입력한 Bearer 토큰을 이 API 호출에 사용 (SwaggerConfig에 정의)
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/matching/requests")
public class MatchRequestController {

    private final MatchRequestCommandService matchRequestCommandService;
    private final MatchRequestBoardService matchRequestBoardService;

    public MatchRequestController(MatchRequestCommandService matchRequestCommandService,
                                  MatchRequestBoardService matchRequestBoardService) {
        this.matchRequestCommandService = matchRequestCommandService;
        this.matchRequestBoardService = matchRequestBoardService;
    }

    // 모집글 생성 — 요청 바디(MatchRequestCreateRequest)에 코스/거리/페이스/만나는 곳 같은
    // 러닝 조건과 일정/대화 수준이 전부 같이 들어온다. 응답 바디는 없고, Location 헤더에
    // 새로 생성된 게시글 id로의 경로가 담긴다(201 Created).
    @Operation(summary = "모집글 등록",
            description = "코스·거리·페이스·만나는 곳 등 러닝 조건과 일정·대화 수준을 한 번에 등록합니다. " +
                    "이미 진행 중인 게시글/신청이 있으면 409로 거부됩니다.")
    @PostMapping
    public ResponseEntity<Void> create(
            @RequestBody MatchRequestCreateRequest request,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        Long id = matchRequestCommandService.create(principal.getUserId(), request);
        return ResponseEntity.created(URI.create("/api/matching/requests/" + id)).build();
    }

    // 내가 등록한 모집글 전체 목록 조회 — 상태별 필터링/화면 탭 매핑은 프론트에서 처리한다.
    @Operation(summary = "내 모집글 전체 목록 조회",
            description = "현재 로그인한 사용자가 지금까지 등록한 모든 모집글을 최신순으로 조회합니다. " +
                    "상태 필터링은 프론트에서 처리합니다.")
    @GetMapping
    public List<MyPostResponse> getMyPosts(
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        return matchRequestBoardService.getMyPosts(principal.getUserId());
    }

    // 내가 지금 갖고 있는 활성 모집글/신청 1건 조회 — 등록 직후 응답의 Location 헤더를
    // 놓쳤거나 id를 모를 때도, 본인 토큰만으로 "지금 내 글이 뭔지" 바로 확인할 수 있게 한다.
    // 활성 상태(SEARCHING/PENDING_CONFIRMATION/MATCHED)가 하나도 없으면 404.
    @Operation(summary = "내 활성 모집글 조회",
            description = "현재 로그인한 사용자가 갖고 있는 활성 모집글(SEARCHING/PENDING_CONFIRMATION/MATCHED)을 " +
                    "1건 조회합니다. 없으면 404가 반환됩니다.")
    @GetMapping("/me")
    public MatchRequestResponse getMyActiveRequest(
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        return matchRequestBoardService.getMyActiveRequest(principal.getUserId());
    }

    // 모집글 상세 조회 — 목록(board)과 달리 meetingPoint까지 포함하고, 조회자가 작성자
    // 본인인지(isOwner)와 대기 중인 신청자 수(pendingApplicantCount)도 같이 내려준다.
    @Operation(summary = "모집글 상세 조회",
            description = "조회자가 작성자 본인이면 isOwner=true와 대기 중인 신청자 수(pendingApplicantCount)가 함께 내려갑니다.")
    @GetMapping("/{id}")
    public MatchRequestResponse getDetail(
            @PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        return matchRequestBoardService.getDetail(id, principal.getUserId());
    }

    // 내 게시글에 온 대기 중인 신청 1건 조회 — applicant-profile, accept/reject
    // API와 이어서 쓰기 위한 activityMatchId를 내려준다. 대기 중인 신청이 없으면 404.
    @Operation(summary = "대기 중인 신청 조회",
            description = "본인 게시글에 온 응답 대기 중(PROPOSED)인 신청 1건을 조회합니다. 없으면 404가 반환됩니다.")
    @GetMapping("/{id}/pending-application")
    public PendingApplicationResponse getPendingApplication(
            @PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        return matchRequestBoardService.getPendingApplication(principal.getUserId(), id);
    }

    // 모집글 수정 — 요청 바디(MatchRequestUpdateRequest)엔 일정(scheduledAt)과 대화 수준만
    // 있다. 코스/거리/페이스/만나는 곳은 이 API로 못 바꾸며 별도 API(러닝 조건 수정) 담당이다.
    @Operation(summary = "모집글 수정",
            description = "일정과 대화 수준만 수정합니다. 코스·거리·페이스·만나는 곳은 러닝 조건 수정 API가 담당하며, " +
                    "모집 중(SEARCHING) 상태일 때만 수정할 수 있습니다.")
    @PatchMapping("/{id}")
    public ResponseEntity<Void> update(
            @PathVariable Long id,
            @RequestBody MatchRequestUpdateRequest request,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        matchRequestCommandService.update(principal.getUserId(), id, request);
        return ResponseEntity.noContent().build();
    }

    // 모집글 자체를 내리는 기능(status → CANCELLED) — 신청 취소(아래
    // MatchRequestBoardController.cancelApplication)와는 다른 기능이니 헷갈리지 말 것.
    // 요청/응답 바디 없음.
    @Operation(summary = "모집글 취소",
            description = "게시글 상태를 CANCELLED로 변경해 모집 탭에서 내립니다. 아래 신청 취소 API와는 다른 기능입니다.")
    @PostMapping("/{id}/cancel")
    public ResponseEntity<Void> cancel(
            @PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        matchRequestCommandService.cancel(principal.getUserId(), id);
        return ResponseEntity.noContent().build();
    }
}
