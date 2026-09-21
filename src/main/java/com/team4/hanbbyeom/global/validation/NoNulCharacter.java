package com.team4.hanbbyeom.global.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// 문자열에 NUL 문자(0x00)가 들어 있으면 검증 실패
// PostgreSQL은 text/varchar에 NUL을 저장하지 못해, 검증 없이 통과시키면 DB 단계에서 예외가 나 500이 된다.
// 사용자가 직접 입력하는 자유 텍스트 필드에 붙여 DB에 닿기 전에 400으로 거절하는 용도
// 사용 예시: @NoNulCharacter(message = "만나는 곳에 사용할 수 없는 문자가 포함되어 있어요.")
@Documented
@Constraint(validatedBy = NoNulCharacterValidator.class)
@Target({
        ElementType.FIELD,
        ElementType.METHOD,
        ElementType.PARAMETER,
        ElementType.ANNOTATION_TYPE,
        ElementType.RECORD_COMPONENT
})
@Retention(RetentionPolicy.RUNTIME)
public @interface NoNulCharacter {

    // 검증 실패 시 기본 오류 메시지이며 사용하는 필드에서 재정의 가능 (응답에 그대로 나가므로 필드에 맞는 문구를 권장)
    String message() default "사용할 수 없는 문자가 포함되어 있어요.";

    // 상황별 검증 규칙 분리에 사용하는 Jakarta Validation 표준 속성 (현재 미사용)
    Class<?>[] groups() default {};

    // 검증 오류에 부가 정보를 전달하는 Jakarta Validation 표준 속성 (현재 미사용)
    Class<? extends Payload>[] payload() default {};
}
