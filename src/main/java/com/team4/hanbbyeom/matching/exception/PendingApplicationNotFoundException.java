package com.team4.hanbbyeom.matching.exception;

// 호스트 본인 게시글에 응답 대기 중(PROPOSED)인 신청이 없을 때 던진다.
// 권한 문제가 아니라 "찾는 리소스 자체가 없음"이므로 404로 처리한다
// (NotMatchParticipantException은 403으로 처리되므로 재사용하지 않고 분리했다).
public class PendingApplicationNotFoundException extends RuntimeException {
    public PendingApplicationNotFoundException(String message) {
        super(message);
    }
}
