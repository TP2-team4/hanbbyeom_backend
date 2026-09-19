package com.team4.hanbbyeom.feedback.exception;

// 아직 후기/신고를 남길 수 없는 상황일 때 던지는 예외.
// - 매칭이 확정(CONFIRMED)된 적 없는 경우 (애초에 활동 자체가 성사되지 않음)
// - 아직 활동 종료 시각(scheduled_end_at)이 지나지 않은 경우
// 두 경우 모두 "지금은 안 되는 요청"이라 GlobalExceptionHandler에서 400으로 매핑할 예정
// (이건 이슈 2에서 처리 — 지금은 예외 클래스만 만들어둠)
public class FeedbackNotAllowedException extends RuntimeException {
    public FeedbackNotAllowedException(String message) {
        super(message);
    }
}