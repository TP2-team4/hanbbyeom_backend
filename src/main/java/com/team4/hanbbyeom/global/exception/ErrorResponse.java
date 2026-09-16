package com.team4.hanbbyeom.global.exception;

// API 에러 응답 공통 형식
// GlobalExceptionHandler가 모든 예외를 이 형식({"message": "..."})으로 통일해서 응답할 때 사용
public record ErrorResponse(String message) {
}
