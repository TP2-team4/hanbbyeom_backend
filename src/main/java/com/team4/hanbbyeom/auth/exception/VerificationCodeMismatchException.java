package com.team4.hanbbyeom.auth.exception;

// 이메일 인증 코드 불일치와 실패 횟수 저장을 구분하기 위한 예외
public class VerificationCodeMismatchException extends IllegalStateException {

    public VerificationCodeMismatchException(String message) {
        // 부모 클래스에 메시지를 전달해 공통 예외 처리에서 e.getMessage()로 사용
        super(message);
    }
}
