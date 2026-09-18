package com.team4.hanbbyeom.chat.exception;

// 사용자는 매칭 참가자이지만 현재 매칭 상태에서는 채팅을 이용할 수 없을 때 사용
// 잘못된 요청 값이나 권한 문제가 아니라 리소스의 현재 상태 문제이므로 409로 응답
public class ChatUnavailableException extends RuntimeException {

    public ChatUnavailableException(String message) {
        super(message);
    }
}
