package com.team4.hanbbyeom.global.exception;

// 요청 값이 허용 범위를 벗어났을 때 사용자에게 보여줄 안내를 담아 던지는 예외

// IllegalArgumentException은 우리 코드뿐 아니라 JDK·라이브러리도 던지고 그 메시지에 내부 클래스명·구현 정보가 담김
// → 공통 예외 처리가 메시지를 그대로 노출하면 의도하지 않은 정보가 응답에 실림
// → 의도한 안내만 이 타입으로 분리해 원문 노출, 나머지 IllegalArgumentException은 공통 문구로 대체
public class InvalidRequestValueException extends RuntimeException {

    public InvalidRequestValueException(String message) {
        // 부모 클래스에 메시지를 전달해 공통 예외 처리에서 e.getMessage()로 사용
        super(message);
    }
}
