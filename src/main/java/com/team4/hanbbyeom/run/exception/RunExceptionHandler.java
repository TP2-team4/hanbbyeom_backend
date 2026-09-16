package com.team4.hanbbyeom.run.exception;


import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import com.team4.hanbbyeom.global.exception.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class RunExceptionHandler {

    @ExceptionHandler(RunMatchConditionNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleRunMatchConditionNotFound(RunMatchConditionNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
    }
}
