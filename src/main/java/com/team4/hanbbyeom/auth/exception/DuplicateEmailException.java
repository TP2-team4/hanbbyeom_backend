package com.team4.hanbbyeom.auth.exception;

// 이미 가입된 이메일로 회원가입을 시도할 때 던지는 예외

// 이메일 형식은 올바르고 이미 존재하는 계정과 충돌하는 경우
// → 400(요청 형식 오류)이 아닌 409로 응답
public class DuplicateEmailException extends RuntimeException {

    public DuplicateEmailException(String message) {
        // 부모 클래스에 메시지를 전달해 공통 예외 처리에서 e.getMessage()로 사용
        super(message);
    }
}
