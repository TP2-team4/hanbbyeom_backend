package com.team4.hanbbyeom.global.validation;

import com.team4.hanbbyeom.auth.dto.SignUpRequest;
import com.team4.hanbbyeom.chat.dto.ChatMessageSendRequest;
import com.team4.hanbbyeom.feedback.domain.NoShowReason;
import com.team4.hanbbyeom.feedback.dto.NoShowReportCreateRequest;
import com.team4.hanbbyeom.feedback.dto.ReviewCreateRequest;
import com.team4.hanbbyeom.matching.domain.TalkLevel;
import com.team4.hanbbyeom.matching.dto.MatchRequestCreateRequest;
import com.team4.hanbbyeom.run.dto.RunConditionCreateRequest;
import com.team4.hanbbyeom.run.dto.RunConditionUpdateRequest;
import com.team4.hanbbyeom.user.domain.DefaultTalkLevel;
import com.team4.hanbbyeom.user.dto.UserNicknameUpdateRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.OffsetDateTime;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

// PostgreSQL은 text/varchar에 NUL(0x00)을 저장하지 못해, 사용자가 직접 입력하는 자유 텍스트 필드에 검증이 없으면
// DB 단계에서 예외가 나 500이 된다. 그런 필드마다 @NoNulCharacter가 붙어 있는지 실제 검증기로 확인한다
// (엔드포인트마다 통합 테스트를 만들지 않고도 필드가 빠지면 바로 드러난다). 새 자유 텍스트 필드를 추가하면 여기 한 줄을 더한다.
// 한 필드만 검증하므로(validateProperty) 다른 필드의 값이나 @NotBlank와 겹치지 않는다
class NulCharacterRequestValidationTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();
    private static final String NUL = String.valueOf((char) 0);

    private record Case(String label, Function<String, Object> request, String property, String message) {
        @Override
        public String toString() {
            return label;
        }
    }

    static Stream<Case> cases() {
        OffsetDateTime start = OffsetDateTime.now().plusDays(2);
        return Stream.of(
                new Case("채팅 메시지 content", ChatMessageSendRequest::new, "content",
                        "메시지에 사용할 수 없는 문자가 포함되어 있어요."),
                new Case("후기 한 줄 comment", value -> new ReviewCreateRequest(5, TalkLevel.SILENT, value), "comment",
                        "후기에 사용할 수 없는 문자가 포함되어 있어요."),
                new Case("노쇼 신고 상세 detail", value -> new NoShowReportCreateRequest(NoShowReason.OTHER, value), "detail",
                        "상세 내용에 사용할 수 없는 문자가 포함되어 있어요."),
                new Case("회원가입 nickname",
                        value -> new SignUpRequest("runner@example.com", "test1234", value, DefaultTalkLevel.SILENT), "nickname",
                        "닉네임에 사용할 수 없는 문자가 포함되어 있어요."),
                new Case("닉네임 변경 nickname", UserNicknameUpdateRequest::new, "nickname",
                        "닉네임에 사용할 수 없는 문자가 포함되어 있어요."),
                new Case("모집글 등록 meetingPoint",
                        value -> new MatchRequestCreateRequest(1L, value, 5000, 8000, 360, 400, start, TalkLevel.SILENT),
                        "meetingPoint", "만나는 곳에 사용할 수 없는 문자가 포함되어 있어요."),
                new Case("러닝 조건 등록 meetingPoint",
                        value -> new RunConditionCreateRequest(1L, 1L, value, 5000, 8000, 360, 400), "meetingPoint",
                        "만나는 곳에 사용할 수 없는 문자가 포함되어 있어요."),
                new Case("러닝 조건 수정 meetingPoint",
                        value -> new RunConditionUpdateRequest(1L, value, 5000, 8000, 360, 400), "meetingPoint",
                        "만나는 곳에 사용할 수 없는 문자가 포함되어 있어요.")
        );
    }

    @ParameterizedTest(name = "{0}: NUL 문자가 있으면 안내 문구와 함께 거절한다")
    @MethodSource("cases")
    void NUL_문자가_있으면_안내_문구와_함께_거절한다(Case target) {
        Object request = target.request().apply("뚝섬" + NUL + "3번 출구");

        var violations = VALIDATOR.validateProperty(request, target.property());

        assertThat(violations).extracting(ConstraintViolation::getMessage).containsExactly(target.message());
    }

    @ParameterizedTest(name = "{0}: NUL 문자가 없으면 통과한다")
    @MethodSource("cases")
    void NUL_문자가_없으면_통과한다(Case target) {
        Object request = target.request().apply("뚝섬 3번 출구");

        assertThat(VALIDATOR.validateProperty(request, target.property())).isEmpty();
    }
}
