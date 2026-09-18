package com.team4.hanbbyeom.chat.dto;

import com.team4.hanbbyeom.chat.domain.ChatMessage;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

// 채팅 메시지 조회·전송 성공 후 클라이언트에 반환하는 응답 데이터
@Schema(description = "채팅 메시지 응답")
public record ChatMessageResponse(
        @Schema(description = "메시지 ID", example = "15")
        Long id,

        @Schema(description = "발신 사용자 ID", example = "3")
        Long senderId,

        @Schema(description = "메시지 내용", example = "2번 출구 앞에 도착했어요.")
        String content,

        @Schema(description = "메시지 생성 시각")
        OffsetDateTime createdAt
) {

    // DB Entity를 외부에 직접 노출하지 않고 API 응답에 필요한 필드만 DTO로 변환
    public static ChatMessageResponse from(ChatMessage message) {
        return new ChatMessageResponse(
                message.getId(),
                message.getSenderId(),
                message.getContent(),
                message.getCreatedAt()
        );
    }
}
