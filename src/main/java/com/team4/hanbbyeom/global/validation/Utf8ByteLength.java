package com.team4.hanbbyeom.global.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// @Size가 글자 수를 검증하는 것과 달리 UTF-8 인코딩 결과의 실제 바이트 길이 검증
// 사용 예시: @Utf8ByteLength(max = 72)

// Java 문서 생성 시 annotation 정보 포함
@Documented

// 실제 바이트 길이 검증을 담당하는 Validator 연결
@Constraint(validatedBy = Utf8ByteLengthValidator.class)

// 일반 필드·메서드·매개변수·다른 annotation·record 구성요소에 사용 가능
@Target({
        ElementType.FIELD,
        ElementType.METHOD,
        ElementType.PARAMETER,
        ElementType.ANNOTATION_TYPE,
        ElementType.RECORD_COMPONENT
})

// 애플리케이션 실행 중 Spring이 annotation을 찾아 요청값을 검증할 수 있도록 유지
@Retention(RetentionPolicy.RUNTIME)

// @Size처럼 필드에 붙여 사용하는 사용자 정의 검증 annotation
public @interface Utf8ByteLength {

    // 검증 실패 시 기본 오류 메시지이며 사용하는 필드에서 재정의 가능
    String message() default "UTF-8 바이트 길이가 허용 범위를 초과했습니다.";

    // 상황별 검증 규칙 분리에 사용하는 Jakarta Validation 표준 속성 (현재 미사용)
    Class<?>[] groups() default {};

    // 검증 오류에 부가 정보를 전달하는 Jakarta Validation 표준 속성 (현재 미사용)
    Class<? extends Payload>[] payload() default {};

    // 허용할 최대 UTF-8 바이트 수
    int max();
}
