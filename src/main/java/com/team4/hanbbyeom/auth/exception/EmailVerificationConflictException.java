package com.team4.hanbbyeom.auth.exception;

// 인증 기록의 현재 상태 때문에 거절되는 요청을 구분하기 위한 예외 (발송 내역 없음, 사용된 코드, 만료된 코드, 시도 횟수 초과)

// 입력값이 잘못된 것이 아니라 기록의 상태가 요청을 허용하지 않는 경우
// → 400(요청 형식 오류)이 아닌 409로 응답해 사용자가 새 인증 코드를 요청해야 함을 알림
public class EmailVerificationConflictException extends RuntimeException {

    public EmailVerificationConflictException(String message) {
        // 부모 클래스에 메시지를 전달해 공통 예외 처리에서 e.getMessage()로 사용
        super(message);
    }
}
