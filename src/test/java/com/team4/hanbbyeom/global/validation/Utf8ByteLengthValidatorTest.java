package com.team4.hanbbyeom.global.validation;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// UTF-8 문자 구성에 따른 바이트 길이 경계 검증
class Utf8ByteLengthValidatorTest {

    // Jakarta Validation의 Validator 생성과 자원 관리를 담당하는 Factory
    private static ValidatorFactory validatorFactory;

    // 객체에 적용된 annotation 제약을 실제로 검사하는 Validator
    private static Validator validator;

    // 모든 테스트에서 함께 사용할 Validator 생성
    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    // 테스트 종료 후 ValidatorFactory 리소스 정리
    @AfterAll
    static void closeValidatorFactory() {
        validatorFactory.close();
    }

    @Test
    @DisplayName("ASCII 72바이트 문자열 허용")
    void ASCII_72바이트_허용() {
        assertThat(validator.validate(new TestRequest("a".repeat(72))))
                .isEmpty();
    }

    @Test
    @DisplayName("ASCII 73바이트 문자열 거부")
    void ASCII_73바이트_거부() {
        assertThat(validator.validate(new TestRequest("a".repeat(73))))
                .singleElement()
                .satisfies(violation -> assertThat(violation.getMessage())
                        .isEqualTo("UTF-8 바이트 길이가 허용 범위를 초과했습니다."));
    }

    @Test
    @DisplayName("한글 24자는 UTF-8 기준 72바이트이므로 허용")
    void 한글_24자_허용() {
        assertThat(validator.validate(new TestRequest("가".repeat(24))))
                .isEmpty();
    }

    @Test
    @DisplayName("한글 25자는 UTF-8 기준 75바이트이므로 거부")
    void 한글_25자_거부() {
        assertThat(validator.validate(new TestRequest("가".repeat(25))))
                .hasSize(1);
    }

    @Test
    @DisplayName("null과 빈 문자열 검증은 다른 제약조건에 위임")
    void null과_빈_문자열_허용() {
        // 필수값 여부는 실제 DTO의 @NotBlank가 담당하므로 바이트 길이 검증에서는 허용
        assertThat(validator.validate(new TestRequest(null))).isEmpty();
        assertThat(validator.validate(new TestRequest(""))).isEmpty();
    }

    // 실제 요청 DTO의 record 구성요소에 annotation을 적용한 상황 재현
    private record TestRequest(
            @Utf8ByteLength(max = 72)
            String value
    ) {
    }
}
