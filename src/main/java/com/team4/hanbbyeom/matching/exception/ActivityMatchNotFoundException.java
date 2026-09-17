package com.team4.hanbbyeom.matching.exception;

// activityMatchId로 조회했는데 해당 매칭(activity_match) 자체가 존재하지 않을 때 던진다.
// 권한 문제가 아니라 "찾는 리소스 자체가 없음"이므로 404로 처리한다
// (NotMatchParticipantException은 403으로 처리되므로, "존재는 하지만 본인과 무관한 매칭"
// 케이스와 구분하기 위해 분리했다 — PR #57 리뷰 피드백: 403 응답에 "존재하지 않는다"는
// 메시지가 섞여 있던 것을 지적받아 상태 코드와 메시지가 일치하도록 나눔).
public class ActivityMatchNotFoundException extends RuntimeException {
    public ActivityMatchNotFoundException(String message) {
        super(message);
    }
}
