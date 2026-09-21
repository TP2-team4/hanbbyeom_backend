package com.team4.hanbbyeom.global.persistence;

import java.sql.SQLException;

// DB 예외가 "유니크 제약 위반"인지 SQLState로 판별한다.
// Spring은 SQLState 클래스 22(데이터 예외: 값 범위 초과, 너무 김, 잘못된 바이트 등)와 23(무결성 제약 위반)을 모두
// DataIntegrityViolationException으로 번역한다. 그래서 그 예외를 잡았다는 이유만으로 "이미 있음"(409)이라고 단정하면,
// 충돌이 아닌 오류(예: 날짜 범위 초과 22008)까지 409로 잘못 안내한다.
// 원인 체인에서 SQLException을 찾아 유니크 위반일 때만 충돌로 본다.
public final class UniqueViolations {

    // PostgreSQL unique_violation. 부분 유니크 인덱스(uq_match_request_active_user 등)도 같은 코드다
    private static final String UNIQUE_VIOLATION = "23505";
    private static final int MAX_CAUSE_DEPTH = 16; // 순환 원인 체인에 대한 안전장치

    private UniqueViolations() {
    }

    public static boolean isUniqueViolation(Throwable e) {
        Throwable current = e;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof SQLException sql && UNIQUE_VIOLATION.equals(sql.getSQLState())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
