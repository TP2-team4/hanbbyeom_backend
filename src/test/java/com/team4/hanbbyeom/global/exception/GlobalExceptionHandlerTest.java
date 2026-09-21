package com.team4.hanbbyeom.global.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

// 핸들러를 직접 호출하는 단위 테스트. IllegalArgumentException 마스킹(#53)은 HTTP 테스트로 확인할 수 없다:
// 요청 DTO의 enum 값(#127)과 요청 파라미터(BoardSort 등)를 서비스가 직접 검증하게 되면서, 사용자 요청으로 도달할 수 있는
// IllegalArgumentException 경로가 없다. 그래도 라이브러리나 새 코드가 던진 예외의 원문이 응답에 실리지 않는다는 보장은
// 핸들러가 지켜야 하므로, 예외를 직접 만들어 넘겨 확인한다.
class GlobalExceptionHandlerTest {

    private static final String INTERNAL_MESSAGE =
            "No enum constant com.team4.hanbbyeom.matching.domain.TalkLevel.TALKATIVE";

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("의도하지 않은 IllegalArgumentException은 원문 대신 고정 문구로 응답한다")
    void 의도하지_않은_IllegalArgumentException은_고정_문구로_응답한다() {
        ResponseEntity<ErrorResponse> response =
                handler.handleIllegalArgument(new IllegalArgumentException(INTERNAL_MESSAGE));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message())
                .isEqualTo("요청 값이 올바르지 않습니다.")
                .doesNotContain("com.team4")
                .doesNotContain("No enum constant");
    }
}
