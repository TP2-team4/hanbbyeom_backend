package com.team4.hanbbyeom.matching.controller;

import com.team4.hanbbyeom.matching.dto.MatchBoardItemResponse;
import com.team4.hanbbyeom.matching.service.MatchRequestBoardService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/match-requests")
public class MatchRequestBoardController {

    private final MatchRequestBoardService matchRequestBoardService;

    public MatchRequestBoardController(MatchRequestBoardService matchRequestBoardService) {
        this.matchRequestBoardService = matchRequestBoardService;
    }

    @GetMapping
    public List<MatchBoardItemResponse> getBoard(
            @RequestParam(required = false) String region,
            @RequestParam(required = false) String talkLevel,
            @RequestParam(required = false) Integer minDistance,
            @RequestParam(required = false) Integer maxDistance,
            @RequestParam(required = false) String datePreset, // "TODAY" | "TOMORROW" | "WEEKEND"
            @RequestHeader("X-USER-ID") Long currentUserId // TODO: 담당 A 인증 방식으로 교체
    ) {
        return matchRequestBoardService.getBoard(region, talkLevel, minDistance, maxDistance, datePreset, currentUserId);
    }
}