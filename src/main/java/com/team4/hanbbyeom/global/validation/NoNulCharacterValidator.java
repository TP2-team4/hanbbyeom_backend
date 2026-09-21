package com.team4.hanbbyeom.global.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

// 문자열을 변경하지 않고 NUL 문자(0x00) 포함 여부만 검증
public class NoNulCharacterValidator implements ConstraintValidator<NoNulCharacter, CharSequence> {

    @Override
    public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
        // null 검증은 필드별 @NotNull·@NotBlank에 위임
        if (value == null) {
            return true;
        }

        return value.chars().noneMatch(character -> character == 0);
    }
}
