package com.team4.hanbbyeom.feedback.exception;

// 같은 활동에 대해 이미 후기/신고를 제출한 사람이 또 제출하려고 할 때 던지는 예외.
// activity_review/no_show_report의 UNIQUE 제약(activity_match_id + 작성자)과 짝을 이루는
// 애플리케이션 레벨 검증용 — DB 제약에만 맡기면 사용자에게 의미 없는 500 에러가 나가버리니,
// Service에서 existsBy로 먼저 확인해서 이 예외를 던지고, GlobalExceptionHandler가
// 409 Conflict로 응답하게 만들 예정 (AlreadyHasActiveMatchRequestException과 같은 패턴)
public class FeedbackAlreadySubmittedException extends RuntimeException {
    public FeedbackAlreadySubmittedException(String message) {
        super(message);
    }
}