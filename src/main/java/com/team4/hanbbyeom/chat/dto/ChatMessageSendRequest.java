package com.team4.hanbbyeom.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 채팅 메시지 전송 시 클라이언트가 보내는 요청 데이터: 메시지 내용
@Schema(description = "채팅 메시지 전송 요청")
public record ChatMessageSendRequest(
        // 발신자 ID는 클라이언트 입력을 신뢰하지 않고 JWT 인증 정보에서 결정
        @Schema(description = "메시지 내용", example = "2번 출구 앞에 도착했어요.", maxLength = 100)
        @NotBlank(message = "메시지를 입력해주세요.")
        @Size(max = 100, message = "메시지는 100자 이하여야 합니다.")
        String content
) {
}
