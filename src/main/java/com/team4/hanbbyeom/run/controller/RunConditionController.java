package com.team4.hanbbyeom.run.controller;

import com.team4.hanbbyeom.run.dto.RunConditionCreateRequest;
import com.team4.hanbbyeom.run.dto.RunConditionResponse;
import com.team4.hanbbyeom.run.dto.RunConditionUpdateRequest;
import com.team4.hanbbyeom.run.service.RunConditionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

// 러닝 조건 RunCondition 관련 API를 제공하는 컨트롤러
// 사용자의 매칭 신청에 대한 러닝 세부 조건(코스, 페이스, 거리, 집결 장소 등)의 등록/조회/수정/삭제 요청을 담당합니다.
@Tag(name = "Run - Condition", description = "러닝 조건 등록/조회/수정/삭제 API")
@RestController
@RequestMapping("/api/run/conditions")
@RequiredArgsConstructor
public class RunConditionController {
    // 러닝 조건 관련 비즈니스 로직을 처리하는 서비스 객체
    private final RunConditionService runConditionService;

    @Operation(summary = "러닝 조건 등록")
    @PostMapping // HTTP POST 요청 매핑 (/api/run/conditions)
    public ResponseEntity<Void> create(
            @Valid @RequestBody RunConditionCreateRequest request,
            @RequestHeader("X-USER-ID") Long currentUserId
            ) {
        // 서비스 계층을 호출하여 러닝 조건 생성 비즈니스 로직을 수행하고, 생성된 매칭 요청 ID(matchRequestId)를 반환받음
        Long matchRequestId = runConditionService.create(currentUserId, request);
        // HTTP Status 201 Created 응답과 함께 생성된 조건의 상세 조회 URI(/api/run/conditions/{id})를 Location 헤더에 담아 반환
        return ResponseEntity.created(URI.create("/api/run/conditions/" + matchRequestId)).build();
    }

    @Operation(summary = "러닝 조건 상세 조회")
    @GetMapping("/{id}")
    public RunConditionResponse getById(
            @PathVariable Long id,
            @RequestHeader("X-USER-ID") Long currentUserId
    ) {
        // 서비스 계층을 호출하여 러닝 조건 상세 조회 비즈니스 로직을 수행하고, 조회 결과를 RunConditionResponse DTO로 반환받음
        return runConditionService.getById(currentUserId, id);
    }

    @Operation(summary = "러닝 조건 수정")
    @PatchMapping("/{id}")
    public ResponseEntity<Void> update(
            @PathVariable Long id,
            @Valid @RequestBody RunConditionUpdateRequest request,
            @RequestHeader("X-USER-ID") Long currentUserId)
    {
        // 서비스 계층을 호출하여 러닝 조건 수정 비즈니스 로직을 수행
        runConditionService.update(currentUserId, id, request);
        // HTTP Status 204 No Content 응답을 반환 (수정 성공 시 본문 없음)
        return ResponseEntity.noContent().build();
    }
}
