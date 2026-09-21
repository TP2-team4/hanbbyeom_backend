package com.team4.hanbbyeom.auth.exception;

// 이메일 인증 처리 중 발생한 서버 내부 장애를 사용자 입력 오류와 구분하기 위한 예외 (메일 발송 실패, 해시 알고리즘 사용 불가)

// IllegalStateException은 공통 예외 처리에서 400으로 매핑, 사용자가 고칠 수 없는 장애인데 입력 오류 응답이 나감
// → RuntimeException을 상속받아 전용 핸들러 없이 공통 Exception 처리로 전달
// → 500 응답 + 고정 문구 + 서버 로그 기록
public class EmailVerificationFailedException extends RuntimeException {

    public EmailVerificationFailedException(String message, Throwable cause) {
        // 원인 예외(메일 라이브러리 등)를 함께 전달해 실패 원인 확인할 수 있도록 함
        super(message, cause);
    }
}
