package com.team4.hanbbyeom.auth.exception;

// 사용자 존재 여부와 재설정 인증 실패 원인을 동일하게 응답하기 위한 예외 (계정 존재 여부 노출 방지)
public class PasswordResetAuthenticationException extends IllegalStateException {

    public static final String MESSAGE =
            "비밀번호 재설정 인증 정보가 올바르지 않거나 만료되었습니다.";

    public PasswordResetAuthenticationException() {
        super(MESSAGE);
    }
}
