package com.team4.hanbbyeom.chat.controller;

import com.team4.hanbbyeom.chat.dto.ChatListItemResponse;
import com.team4.hanbbyeom.chat.service.ChatMessageService;
import com.team4.hanbbyeom.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// 인증된 사용자가 참여한 1:1 채팅 목록 조회 API
@Tag(name = "Chat", description = "채팅 목록 API")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/chats")
@RequiredArgsConstructor
public class ChatListController {

    private final ChatMessageService chatMessageService; // Controller가 사용할 Service 객체

    // 채팅 목록 조회: GET /api/chats
    @Operation(
            summary = "채팅 목록 조회",
            description = "확정된 매칭의 채팅을 최근 메시지 시각 순으로 반환합니다. " +
                    "메시지가 없으면 매칭 확정 시각을 정렬 기준으로 사용합니다."
    )
    @GetMapping
    public List<ChatListItemResponse> getChatList(
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        // JWT 인증 사용자 ID를 Service에 전달해 본인이 실제 참가한 채팅 목록만 조회
        return chatMessageService.getChatList(principal.getUserId());
    }
}
