package com.team4.hanbbyeom.global.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

// Spring이 SQLState 클래스 22(데이터 예외)와 23(무결성 제약 위반)을 모두 DataIntegrityViolationException으로 번역하므로,
// 유니크 위반(23505)만 골라내는지 확인한다. 실제 Postgres 예외 체인(DataIntegrityViolationException →
// Hibernate 예외 → PSQLException)에서도 인식되는지는 등록 충돌 HTTP 테스트(MatchRequestValidationIntegrationTest)가 확인한다.
class UniqueViolationsTest {

    @Test
    @DisplayName("SQLState 23505는 유니크 위반이다")
    void SQLState_23505는_유니크_위반이다() {
        assertThat(UniqueViolations.isUniqueViolation(new SQLException("duplicate key", "23505"))).isTrue();
    }

    @Test
    @DisplayName("여러 단계로 감싸진 원인 체인 안의 23505도 찾는다")
    void 감싸진_원인_체인_안의_23505도_찾는다() {
        Throwable chain = new DataIntegrityViolationException("outer",
                new RuntimeException("hibernate", new SQLException("duplicate key", "23505")));

        assertThat(UniqueViolations.isUniqueViolation(chain)).isTrue();
    }

    @Test
    @DisplayName("유니크 위반이 아닌 SQLState는 충돌로 보지 않는다")
    void 유니크_위반이_아닌_SQLState는_충돌이_아니다() {
        // 22008 날짜 범위 초과, 22001 값이 너무 김, 22021 잘못된 바이트(NUL), 23502 NOT NULL, 23503 외래 키, 23514 CHECK
        for (String sqlState : new String[]{"22008", "22001", "22021", "23502", "23503", "23514"}) {
            Throwable chain = new DataIntegrityViolationException("outer", new SQLException("db error", sqlState));

            assertThat(UniqueViolations.isUniqueViolation(chain)).as("SQLState %s", sqlState).isFalse();
        }
    }

    @Test
    @DisplayName("SQL 원인이 없거나 SQLState가 없으면 충돌로 보지 않는다")
    void SQL_원인이_없으면_충돌이_아니다() {
        assertThat(UniqueViolations.isUniqueViolation(new DataIntegrityViolationException("원인 없음"))).isFalse();
        assertThat(UniqueViolations.isUniqueViolation(new SQLException("state 없음"))).isFalse();
        assertThat(UniqueViolations.isUniqueViolation(null)).isFalse();
    }

    @Test
    @DisplayName("원인 체인이 순환해도 무한 반복하지 않는다")
    void 원인_체인이_순환해도_끝난다() {
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second", first);
        first.initCause(second);

        assertThat(UniqueViolations.isUniqueViolation(first)).isFalse();
    }
}
