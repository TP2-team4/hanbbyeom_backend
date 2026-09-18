package com.team4.hanbbyeom.chat.controller;

import com.team4.hanbbyeom.chat.dto.ChatMessageResponse;
import com.team4.hanbbyeom.chat.dto.ChatMessageSendRequest;
import com.team4.hanbbyeom.chat.service.ChatMessageService;
import com.team4.hanbbyeom.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// 확정된 매칭 참가자의 채팅 메시지 조회 및 전송 API
@Tag(name = "Chat - Messages", description = "확정된 매칭 참가자의 1:1 채팅 API")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/matching/matches/{activityMatchId}/messages")
@RequiredArgsConstructor
public class ChatMessageController {

    private final ChatMessageService chatMessageService; // Controller가 사용할 Service 객체

    // 채팅 메시지 조회: GET /api/matching/matches/{activityMatchId}/messages
    @Operation(
            summary = "채팅 메시지 조회",
            description = "afterId가 없거나 0이면 전체 메시지, 값이 있으면 해당 ID 이후 메시지를 반환합니다."
    )
    @GetMapping
    public List<ChatMessageResponse> getMessages(
            @PathVariable Long activityMatchId,
            @RequestParam(defaultValue = "0") long afterId,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        // JWT 인증 사용자와 조회할 매칭·마지막 수신 메시지 ID를 Service에 전달
        // Service가 참가 권한과 채팅 개방 상태를 확인한 뒤 조회 결과를 응답 DTO 목록으로 반환
        return chatMessageService
                .getMessages(principal.getUserId(), activityMatchId, afterId);
    }

    // 채팅 메시지 전송: POST /api/matching/matches/{activityMatchId}/messages
    @Operation(
            summary = "채팅 메시지 전송",
            description = "확정된 매칭이 종료되기 전까지 참가자가 메시지를 전송할 수 있습니다."
    )
    @PostMapping
    public ResponseEntity<ChatMessageResponse> sendMessage(
            @PathVariable Long activityMatchId,
            @Valid @RequestBody ChatMessageSendRequest request,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        // Service에게 JWT 인증 사용자와 매칭 ID 및 메시지 내용을 넘겨서 전송을 처리시키고,
        // 저장된 메시지 정보를 response라는 변수에 저장
        ChatMessageResponse response = chatMessageService.sendMessage(
                principal.getUserId(),
                activityMatchId,
                request
        );

        // body에 저장된 메시지 정보를 담아 201 Created 반환
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }
}
