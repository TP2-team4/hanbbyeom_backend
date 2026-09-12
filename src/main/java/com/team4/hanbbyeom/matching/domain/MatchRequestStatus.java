package com.team4.hanbbyeom.matching.domain;

public enum MatchRequestStatus {
    SEARCHING,              // 모집 중 (모집 탭에 노출)
    PENDING_CONFIRMATION,   // 누군가 신청해서 호스트 응답 대기 중
    MATCHED,                // 확정됨
    CANCELLED,              // 작성자가 취소함
    EXPIRED,                // 응답/검색 기한을 넘겨 자동 만료
    CLOSED                  // 기타 종료
}
