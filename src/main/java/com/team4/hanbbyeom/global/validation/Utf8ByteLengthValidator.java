package com.team4.hanbbyeom.global.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.nio.charset.StandardCharsets;

// 문자열을 변경하지 않고 UTF-8 인코딩 결과의 실제 바이트 길이 검증
public class Utf8ByteLengthValidator implements ConstraintValidator<Utf8ByteLength, String> {

    private int max;

    @Override
    public void initialize(Utf8ByteLength constraintAnnotation) {
        this.max = constraintAnnotation.max();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // null과 빈 문자열 검증은 필드별 @NotNull·@NotBlank에 위임
        if (value == null || value.isEmpty()) {
            return true;
        }

        return value.getBytes(StandardCharsets.UTF_8).length <= max;
    }
}
