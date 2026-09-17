package com.team4.hanbbyeom.user.domain;

import io.swagger.v3.oas.annotations.media.Schema;

// 모집글 작성 시 기본 선택값과 내 정보 화면에 표시되는 사용자 설정
// Enum 이름이 DB와 API에 그대로 사용되므로 값 변경 시 마이그레이션과 API 계약도 함께 수정해야 함
@Schema(description = "사용자의 기본 대화 수준")
public enum DefaultTalkLevel {
    SILENT,     // 대화 없이 각자 달려요
    LIGHT_CHAT  // 가벼운 대화는 괜찮아요
}
