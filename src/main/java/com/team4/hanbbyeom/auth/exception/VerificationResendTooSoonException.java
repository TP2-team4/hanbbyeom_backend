package com.team4.hanbbyeom.auth.exception;

// 재발송 대기시간(60초)이 지나기 전에 인증 코드를 다시 요청할 때 던지는 예외

// 대기시간이 지나면 같은 요청이 그대로 성공하는 일시적 제한
// → 409(새 요청이 필요한 상태 충돌)와 구분해 429로 응답, 프론트가 대기 후 재시도로 안내 가능
// 가입 여부 비노출을 위해 PASSWORD_RESET 목적은 제한 중에도 정상 종료하므로 SIGNUP에서만 발생
public class VerificationResendTooSoonException extends RuntimeException {

    public VerificationResendTooSoonException(String message) {
        // 부모 클래스에 메시지를 전달해 공통 예외 처리에서 e.getMessage()로 사용
        super(message);
    }
}
