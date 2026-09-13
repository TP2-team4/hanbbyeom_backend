package com.team4.hanbbyeom.matching.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class MatchingExceptionHandler {

    @ExceptionHandler(InvalidMatchRequestException.class)
    public ResponseEntity<ErrorResponse> handleInvalid(InvalidMatchRequestException e) {
        return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
    }

    @ExceptionHandler(AlreadyHasActiveMatchRequestException.class)
    public ResponseEntity<ErrorResponse> handleConflict(AlreadyHasActiveMatchRequestException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(e.getMessage()));
    }

    @ExceptionHandler(MatchRequestNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(MatchRequestNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorResponse(e.getMessage()));
    }
}

record ErrorResponse(String message) {}