package com.team4.hanbbyeom.matching.controller;

import com.team4.hanbbyeom.matching.dto.MatchRequestCreateRequest;
import com.team4.hanbbyeom.matching.dto.MatchRequestDetailResponse;
import com.team4.hanbbyeom.matching.dto.MatchRequestUpdateRequest;
import com.team4.hanbbyeom.matching.service.MatchRequestBoardService;
import com.team4.hanbbyeom.matching.service.MatchRequestCommandService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
@RequestMapping("/api/match-requests")
public class MatchRequestController {

    private final MatchRequestCommandService matchRequestCommandService;
    private final MatchRequestBoardService matchRequestBoardService;

    public MatchRequestController(MatchRequestCommandService matchRequestCommandService,
                                  MatchRequestBoardService matchRequestBoardService) {
        this.matchRequestCommandService = matchRequestCommandService;
        this.matchRequestBoardService = matchRequestBoardService;
    }

    @PostMapping
    public ResponseEntity<Void> create(
            @RequestBody MatchRequestCreateRequest request,
            @RequestHeader("X-USER-ID") Long currentUserId
    ) {
        Long id = matchRequestCommandService.create(currentUserId, request);
        return ResponseEntity.created(URI.create("/api/match-requests/" + id)).build();
    }

    @GetMapping("/{id}")
    public MatchRequestDetailResponse getDetail(
            @PathVariable Long id,
            @RequestHeader("X-USER-ID") Long currentUserId
    ) {
        return matchRequestBoardService.getDetail(id, currentUserId);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<Void> update(
            @PathVariable Long id,
            @RequestBody MatchRequestUpdateRequest request,
            @RequestHeader("X-USER-ID") Long currentUserId
    ) {
        matchRequestCommandService.update(currentUserId, id, request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<Void> cancel(
            @PathVariable Long id,
            @RequestHeader("X-USER-ID") Long currentUserId
    ) {
        matchRequestCommandService.cancel(currentUserId, id);
        return ResponseEntity.noContent().build();
    }
}