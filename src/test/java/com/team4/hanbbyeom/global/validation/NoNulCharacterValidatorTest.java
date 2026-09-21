package com.team4.hanbbyeom.global.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class NoNulCharacterValidatorTest {

    private final NoNulCharacterValidator validator = new NoNulCharacterValidator();

    @ParameterizedTest(name = "NUL 문자가 있는 값은 거절한다 (길이 {0}자)")
    @ValueSource(strings = {"a\0b", "\0", "뚝섬\0", "끝에 붙음\0", "\0앞에 붙음"})
    @DisplayName("NUL 문자가 하나라도 있으면 검증에 실패한다")
    void NUL_문자가_있으면_실패한다(String value) {
        assertThat(validator.isValid(value, null)).isFalse();
    }

    @ParameterizedTest(name = "NUL 문자가 없는 값은 통과한다: {0}")
    @ValueSource(strings = {"", " ", "뚝섬유원지역 3번 출구", "abc", "줄바꿈\n탭\t포함", "이모지 😀", "0", "\\u0000"})
    @DisplayName("NUL 문자가 없으면 다른 특수 문자가 있어도 통과한다")
    void NUL_문자가_없으면_통과한다(String value) {
        assertThat(validator.isValid(value, null)).isTrue();
    }

    @Test
    @DisplayName("null 검증은 @NotNull·@NotBlank에 맡기고 통과시킨다")
    void null은_통과한다() {
        assertThat(validator.isValid(null, null)).isTrue();
    }
}
