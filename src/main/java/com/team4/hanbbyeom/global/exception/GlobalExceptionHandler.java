package com.team4.hanbbyeom.global.exception;

import com.team4.hanbbyeom.chat.exception.ChatUnavailableException;
import com.team4.hanbbyeom.feedback.exception.FeedbackAlreadySubmittedException;
import com.team4.hanbbyeom.feedback.exception.FeedbackNotAllowedException;
import com.team4.hanbbyeom.matching.exception.*;
import com.team4.hanbbyeom.run.exception.RunMatchConditionNotFoundException;
import com.team4.hanbbyeom.user.exception.WithdrawPasswordMismatchException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

// 모든 Controller에서 발생하는 예외를 ErrorResponse 형식으로 통일해서 응답
// 전제조건: SecurityConfig에서 /error 경로를 permitAll로 열어둬야 함
// (안 열면 Spring이 내부적으로 /error로 전달하는 과정에서 Security에 막혀 403으로 바뀜)
@RestControllerAdvice
public class GlobalExceptionHandler {

    // Logger: (SLF4J 제공) 로그를 남기는 도구
    // 로그 레벨(error/warn/info) 구분, 시간·클래스명 자동 기록 등을 지원
    // getLogger(GlobalExceptionHandler.class):
    // 이 로그가 어느 클래스에서 찍힌 건지 표시하기 위해 클래스 정보를 넘겨서 이 클래스 전용 Logger 객체를 만듦
    private static final Logger log = LoggerFactory
            .getLogger(GlobalExceptionHandler.class);

    // COMMON: 요청 DTO 검증 실패 처리
    // 검증 실패 시, 발생한 필드 오류 중 첫 번째 메시지를 응답으로 사용
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(
            MethodArgumentNotValidException e // MethodArgumentNotValidException: Controller 메서드의 요청 DTO가 @Valid 검증에 실패
    ) {
        String message = e.getBindingResult() // Validation 결과를 가져옴 (실패한 필드, 실패 이유 등)
                .getFieldErrors() // 필드 단위 Validation 오류 목록을 가져옴 (반환값 List)
                .stream() // List에 들어 있는 값들을 하나씩 처리
                .findFirst() // 오류가 여러 개 있더라도 첫 번째 오류 하나만 가져옴
                .map(fieldError -> fieldError.getDefaultMessage()) // 가져온 FieldError 객체에서 Validation 메시지만 꺼냄
                .orElse("요청 값이 올바르지 않습니다."); // 오류메시지가 없을 경우 사용할 기본 메시지

        // Validation 실패 응답
        return ResponseEntity
                .badRequest() // HTTP 상태코드를 400 Bad Request로 설정
                .body(new ErrorResponse(message)); // HTTP Response Body 설정: 오류 메시지 든 ErrorResponse 객체 생성
    }

    // COMMON: 요청 값의 형식 변환 실패 처리
    // 잘못된 JSON·Enum 값 또는 숫자 자리에 문자열을 보낸 경로·쿼리 파라미터에 해당
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> handleRequestConversionFailure(Exception e) {
        return ResponseEntity
                .badRequest()
                .body(new ErrorResponse("요청 형식이 올바르지 않습니다."));
    }

    // COMMON: 비즈니스 상태 규칙 위반 처리
    // 현재 상태에서 요청 수행 불가할 때 던지는 예외(재발송 대기, 만료, 시도 초과, 코드 불일치 등)
    // e.getMessage()를 그대로 노출하는 이유: 우리 Service 코드가 사용자에게 보여줄 목적으로 작성한 문구라서 안전
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(
            IllegalStateException e // IllegalStateException: 값보다는 현재 상태가 문제
    ) {
        return ResponseEntity
                .badRequest() // HTTP 상태코드를 400 Bad Request로 설정
                .body(new ErrorResponse(e.getMessage())); // Service가 던진 메시지를 그대로 응답 body에 담음
    }

    // COMMON: 잘못된 메서드 인자 처리
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(
            IllegalArgumentException e // IllegalArgumentException: 넘겨준 값 자체가 잘못됨
    ) {
        return ResponseEntity
                .badRequest() // HTTP 상태코드를 400 Bad Request로 설정
                .body(new ErrorResponse(e.getMessage())); // 의도한 비즈니스 예외이므로 Service 메시지를 그대로 응답
    }

    // MATCHING: 매칭 요청 값 검증 실패 처리
    @ExceptionHandler(InvalidMatchRequestException.class)
    public ResponseEntity<ErrorResponse> handleInvalidMatchRequest(InvalidMatchRequestException e) {
        return ResponseEntity
                .badRequest()
                .body(new ErrorResponse(e.getMessage()));
    }

    // MATCHING: 활성 매칭 요청 중복 생성 처리
    @ExceptionHandler(AlreadyHasActiveMatchRequestException.class)
    public ResponseEntity<ErrorResponse> handleAlreadyHasActiveMatchRequest(AlreadyHasActiveMatchRequestException e) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(new ErrorResponse(e.getMessage()));
    }

    // MATCHING: 매칭 요청 미존재 처리
    @ExceptionHandler(MatchRequestNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleMatchRequestNotFound(MatchRequestNotFoundException e) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse(e.getMessage()));
    }

    // MATCHING: 검색 중이 아닌 매칭 요청 처리
    @ExceptionHandler(MatchRequestNotSearchingException.class)
    public ResponseEntity<ErrorResponse> handleMatchRequestNotSearching(MatchRequestNotSearchingException e) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(new ErrorResponse(e.getMessage()));
    }

    // MATCHING: 탈퇴한 신청자의 신청을 수락하려는 경우 처리
    // 수락은 막고 거절은 가능하므로, 호스트가 사유와 다음 행동을 알 수 있게 메시지를 그대로 응답
    @ExceptionHandler(ApplicantWithdrawnException.class)
    public ResponseEntity<ErrorResponse> handleApplicantWithdrawn(ApplicantWithdrawnException e) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(new ErrorResponse(e.getMessage()));
    }

    // MATCHING: 매칭 참가자 권한 없음 처리
    @ExceptionHandler(NotMatchParticipantException.class)
    public ResponseEntity<ErrorResponse> handleNotMatchParticipant(NotMatchParticipantException e) {
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse(e.getMessage()));
    }

    // MATCHING: 응답 대기 중인 신청 미존재 처리
    // 권한 문제가 아니라 리소스 미존재이므로 매칭 참가자 권한 없음(403)과 분리해서 404로 응답
    @ExceptionHandler(PendingApplicationNotFoundException.class)
    public ResponseEntity<ErrorResponse> handlePendingApplicationNotFound(PendingApplicationNotFoundException e) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse(e.getMessage()));
    }

    // MATCHING: activityMatchId에 해당하는 매칭 미존재 처리
    // 존재하지만 본인과 무관한 매칭의 403 응답과 구분해서 404로 응답한다.
    // 원래 이 케이스도 NotMatchParticipantException
    // (403)으로 던지면서 메시지만 "존재하지 않는 매칭이에요"라고 되어 있어 상태 코드와 메시지가
    // 어긋나 있었다(PR #57 리뷰 피드백으로 발견).
    @ExceptionHandler(ActivityMatchNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleActivityMatchNotFound(ActivityMatchNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
    }

    // CHAT: 채팅 이용 불가 상태 처리
    // 매칭이 아직 확정되지 않았거나 현재 상태·전송 가능 시간이 메시지 전송을 허용하지 않는 경우에 해당
    @ExceptionHandler(ChatUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleChatUnavailable(ChatUnavailableException e) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(new ErrorResponse(e.getMessage()));
    }

    // AUTH: 로그인 인증 실패 처리
    // AuthenticationManager는 "사용자 없음"도 BadCredentialsException으로 바꿔서 던짐
    // → 두 경우가 애초에 같은 예외로 도착하고, 여기서 고정 문구를 쓰므로 응답도 완전히 동일
    //   이 핸들러가 없으면 최종 핸들러로 떨어져 500이 나감
    //   이메일/비밀번호 오타는 사용자의 정상적인 실수이므로, 서버 오류(500)와 구분해서 인증 실패(401)로 응답
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleBadCredentials(BadCredentialsException e) {
        // e.getMessage()를 쓰지 않는 이유: Spring이 넣는 기본 문구("Bad credentials")가 그대로 노출됨

        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED) // 인증 자체가 실패한 것이므로 401
                .body(new ErrorResponse("이메일 또는 비밀번호가 올바르지 않습니다."));
    }

    // USER: 회원 탈퇴 시 비밀번호 재확인 실패 처리
    // 토큰은 유효한 상태이므로 로그인 실패(401)와 구분해서 403으로 응답
    @ExceptionHandler(WithdrawPasswordMismatchException.class)
    public ResponseEntity<ErrorResponse> handleWithdrawPasswordMismatch(WithdrawPasswordMismatchException e) {
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse(e.getMessage()));
    }

    // SECURITY: 인증 후 리소스 접근 권한 없음 처리
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException e) {
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse(e.getMessage()));
    }

    // RUN: 존재하지 않는 러닝 조건(Run 도메인) 조회/수정/삭제 시도
    // 원래 RunExceptionHandler(별도 @RestControllerAdvice)에 있었으나, 서로 다른
    // @RestControllerAdvice로 나뉘면 Spring이 먼저 평가되는 Advice 빈에서 매칭되는
    // 핸들러를 찾는 순간 멈춰버려서, catch-all이 있는 이 클래스가 먼저 평가될 경우
    // 이 핸들러까지 도달하지 못하고 마지막 catch-all(500)로 빠지는 문제가 있었음
    // → 모든 구체적인 핸들러를 이 클래스 하나에 모아서 그런 순서 의존성을 없앤다
    @ExceptionHandler(RunMatchConditionNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleRunMatchConditionNotFound(RunMatchConditionNotFoundException e) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse(e.getMessage()));
    }

    // FEEDBACK: 후기/노쇼 신고를 아직 제출할 수 없는 상태 처리
    // 매칭이 확정된 적 없거나, 확정됐어도 아직 활동 종료 시각(scheduledEndAt)이 지나지 않은 경우
    @ExceptionHandler(FeedbackNotAllowedException.class)
    public ResponseEntity<ErrorResponse> handleFeedbackNotAllowed(FeedbackNotAllowedException e) {
        return ResponseEntity
                .badRequest()
                .body(new ErrorResponse(e.getMessage()));
    }

    // FEEDBACK: 같은 활동에 대해 후기/신고를 중복 제출 처리
    // AlreadyHasActiveMatchRequestException과 동일한 이유로 리소스 충돌(409)로 응답
    @ExceptionHandler(FeedbackAlreadySubmittedException.class)
    public ResponseEntity<ErrorResponse> handleFeedbackAlreadySubmitted(FeedbackAlreadySubmittedException e) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(new ErrorResponse(e.getMessage()));
    }

    // COMMON: 예상하지 못한 예외 처리
    // 위 핸들러들 중 어디에도 안 걸리는 모든 예외가 마지막으로 여기서 잡힘
    // 의도해서 던진 비즈니스 예외와 달리 e.getMessage()를 응답에 넣지 않는 이유
    // → 이런 예외는 우리가 의도해서 던진 게 아니라서 메시지 안에 내부 구현이 그대로 담겨 있을 수 있음
    // → 그 내용을 클라이언트에 그대로 보여주면 정보 노출 위험이 있어 고정된 안전한 문구만 반환
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        // 클라이언트에는 안전한 문구만 보여주되, 서버 로그에는 어디서 어떤 순서로 예외가 터졌는지 전부 남김
        log.error("예상하지 못한 예외 발생", e);

        return ResponseEntity
                .internalServerError() // HTTP 상태코드를 500 Internal Server Error로 설정
                .body(new ErrorResponse("서버 오류가 발생했습니다.")); // 고정된 안전한 메시지만 반환
    }
}
