package com.team4.hanbbyeom.run.controller;

import com.team4.hanbbyeom.run.dto.RunConditionCreateRequest;
import com.team4.hanbbyeom.run.service.RunConditionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@Tag(name = "Run - Condition", description = "러닝 조건 등록/조회/수정/삭제 API")
@RestController
@RequestMapping("/api/run/conditions")
@RequiredArgsConstructor
public class RunConditionController {
    private final RunConditionService runConditionService;

    @Operation(summary = "러닝 조건 등록")
    @PostMapping
    public ResponseEntity<Void> create(
            @Valid @RequestBody RunConditionCreateRequest request,
            @RequestHeader("X-USER-ID") Long currentUserId
            ) {
        Long matchRequestId = runConditionService.create(currentUserId, request);
        return ResponseEntity.created(URI.create("/api/run/conditions/" + matchRequestId)).build();
    }
}
