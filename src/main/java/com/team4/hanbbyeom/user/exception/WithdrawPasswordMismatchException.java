package com.team4.hanbbyeom.user.exception;

// 회원 탈퇴 시 입력한 비밀번호가 본인 비밀번호와 일치하지 않을 때 사용
// Access Token 자체는 유효하므로 401이 아니라 403으로 응답
// (401을 쓰면 클라이언트가 토큰 만료로 오해해 로그아웃 처리할 수 있음)
public class WithdrawPasswordMismatchException extends RuntimeException {

    public WithdrawPasswordMismatchException() {
        super("비밀번호가 올바르지 않습니다.");
    }
}
