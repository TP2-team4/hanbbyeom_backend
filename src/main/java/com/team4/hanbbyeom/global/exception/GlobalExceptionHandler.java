package com.team4.hanbbyeom.global.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.team4.hanbbyeom.matching.exception.AlreadyHasActiveMatchRequestException;
import com.team4.hanbbyeom.matching.exception.InvalidMatchRequestException;
import com.team4.hanbbyeom.matching.exception.MatchRequestNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import com.team4.hanbbyeom.matching.exception.MatchRequestNotSearchingException;
import com.team4.hanbbyeom.matching.exception.NoActiveMatchRequestException;
import com.team4.hanbbyeom.matching.exception.NotMatchParticipantException;

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

    // 1. @Valid 검증 실패를 처리하는 핸들러
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

    // 2. Service에서 던지는 비즈니스 규칙 위반을 처리하는 핸들러
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

    // 3. 메서드에 전달된 인자 값이 올바르지 않을 때, 잘못된 인자 값을 처리하는 핸들러
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(
            IllegalArgumentException e // IllegalArgumentException: 넘겨준 값 자체가 잘못됨
    ) {
        return ResponseEntity
                .badRequest() // HTTP 상태코드를 400 Bad Request로 설정
                .body(new ErrorResponse(e.getMessage())); // 2번과 동일한 이유로 e.getMessage()를 그대로 노출
    }

    // 4. 매칭 도메인의 잘못된 요청 값 처리
    @ExceptionHandler(InvalidMatchRequestException.class)
    public ResponseEntity<ErrorResponse> handleInvalidMatchRequest(InvalidMatchRequestException e) {
        return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
    }

    // 5. 이미 활성 매칭 요청(게시글)이 있는 상태에서 새로 생성을 시도할 때
    @ExceptionHandler(AlreadyHasActiveMatchRequestException.class)
    public ResponseEntity<ErrorResponse> handleAlreadyHasActiveMatchRequest(AlreadyHasActiveMatchRequestException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(e.getMessage()));
    }

    // 6. 존재하지 않는 매칭 요청(게시글) 조회/수정/취소 시도
    @ExceptionHandler(MatchRequestNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleMatchRequestNotFound(MatchRequestNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
    }

    // 7. 본인 소유가 아닌 리소스에 접근을 시도할 때
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorResponse(e.getMessage()));
    }

    // 8. 예상하지 못한 예외를 처리하는 최종 핸들러
    // 위 세 핸들러 중 어디에도 안 걸리는 모든 예외가 마지막으로 여기서 잡힘
    // 2·3번과 달리 e.getMessage()를 응답에 넣지 않는 이유
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

    // 9. 매칭 신청 시 본인 활성 모집글이 없을 때
    @ExceptionHandler(NoActiveMatchRequestException.class)
    public ResponseEntity<ErrorResponse> handleNoActiveMatchRequest(NoActiveMatchRequestException e) {
        return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
    }

    // 10. 이미 마감/진행 중인 게시글에 신청하거나, 확정된 매칭을 취소하려 할 때
    @ExceptionHandler(MatchRequestNotSearchingException.class)
    public ResponseEntity<ErrorResponse> handleMatchRequestNotSearching(MatchRequestNotSearchingException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(e.getMessage()));
    }

    // 11. 본인과 무관한 매칭의 신청자 프로필을 조회하려 할 때
    @ExceptionHandler(NotMatchParticipantException.class)
    public ResponseEntity<ErrorResponse> handleNotMatchParticipant(NotMatchParticipantException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorResponse(e.getMessage()));
    }
}
