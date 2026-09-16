package com.team4.hanbbyeom.matching.controller;

import com.team4.hanbbyeom.global.security.CustomUserDetails;
import com.team4.hanbbyeom.matching.dto.MatchRequestCreateRequest;
import com.team4.hanbbyeom.matching.dto.MatchRequestResponse;
import com.team4.hanbbyeom.matching.dto.MatchRequestUpdateRequest;
import com.team4.hanbbyeom.matching.service.MatchRequestBoardService;
import com.team4.hanbbyeom.matching.service.MatchRequestCommandService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

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
    @PostMapping
    public ResponseEntity<Void> create(
            @RequestBody MatchRequestCreateRequest request,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        Long id = matchRequestCommandService.create(principal.getUserId(), request);
        return ResponseEntity.created(URI.create("/api/matching/requests/" + id)).build();
    }

    // 모집글 상세 조회 — 목록(board)과 달리 meetingPoint까지 포함하고, 조회자가 작성자
    // 본인인지(isOwner)와 대기 중인 신청자 수(pendingApplicantCount)도 같이 내려준다.
    @GetMapping("/{id}")
    public MatchRequestResponse getDetail(
            @PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        return matchRequestBoardService.getDetail(id, principal.getUserId());
    }

    // 모집글 수정 — 요청 바디(MatchRequestUpdateRequest)엔 일정(scheduledAt)과 대화 수준만
    // 있다. 코스/거리/페이스/만나는 곳은 이 API로 못 바꾸며 별도 API(러닝 조건 수정) 담당이다.
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
    @PostMapping("/{id}/cancel")
    public ResponseEntity<Void> cancel(
            @PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        matchRequestCommandService.cancel(principal.getUserId(), id);
        return ResponseEntity.noContent().build();
    }
}
