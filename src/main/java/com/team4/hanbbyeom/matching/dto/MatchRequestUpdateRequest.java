package com.team4.hanbbyeom.matching.dto;

import com.team4.hanbbyeom.matching.domain.TalkLevel;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;

// 모집글 수정 요청. 일정과 대화 수준은 둘 다 필수다 — 서비스가 두 값을 항상 그대로 반영하므로(부분 수정이 아니다)
// 빠지면 NullPointerException으로 500이 나갔다. 컨트롤러의 @Valid가 누락을 400으로 걸러낸다.
public record MatchRequestUpdateRequest(
        @NotNull(message = "활동 시작 시각을 입력해주세요.")
        OffsetDateTime scheduledAt,
        // JSON에서는 "SILENT" | "LIGHT_CHAT" 문자열 그대로다. 그 외 값은 JSON 변환 단계에서 걸러져 400이 된다
        @NotNull(message = "대화 수준을 선택해주세요.")
        TalkLevel talkLevel
) {}
