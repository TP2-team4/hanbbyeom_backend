package com.team4.hanbbyeom.matching.domain;

public enum ActivityMatchStatus {
    PROPOSED,       // 신청 접수, 호스트 응답 대기
    CONFIRMED,      // 호스트가 수락해서 확정됨
    REJECTED,       // 호스트가 거절함
    CANCELLED,      // 확정 후 취소됨 (참가자가 직접 취소하거나, 참가자 회원 탈퇴 시 시스템이 처리)
    EXPIRED,        // 응답 기한을 넘겨 자동 만료
    ENDED           // 활동 시간이 지나 자연 종료
}
