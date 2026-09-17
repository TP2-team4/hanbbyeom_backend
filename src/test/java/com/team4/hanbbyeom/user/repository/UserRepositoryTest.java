package com.team4.hanbbyeom.user.repository;

import com.team4.hanbbyeom.user.domain.DefaultTalkLevel;
import com.team4.hanbbyeom.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// User Entity와 이메일 조회 쿼리 메서드의 PostgreSQL 연동 검증

@SpringBootTest // Spring Boot 애플리케이션 전체 컨텍스트를 띄워서 테스트하는 어노테이션
@Transactional // 테스트 메서드가 끝나면 DB 변경 내용을 롤백
class UserRepositoryTest {

    @Autowired // Spring이 필요한 객체를 자동으로 주입해주는 어노테이션
    private UserRepository userRepository;

    @Test // 테스트 메서드
    @DisplayName("탈퇴하지 않은 사용자를 이메일로 조회") // 테스트 결과 화면에서 읽기 좋은 이름으로 보여줌
    void findByEmailAndDeletedAtIsNullReturnsUser() {
        // 기존 데이터와 이메일이 겹치지 않는 테스트 사용자 생성
        String email = "repository-" + UUID.randomUUID() + "@example.com";
        User user = new User(
                email,
                "encoded-password", // 테스트 목적이 "비밀번호 암호화 검증"이 아니라 Repository 저장/조회 검증
                "테스트사용자",
                DefaultTalkLevel.SILENT,
                Instant.now()
        );

        // INSERT 즉시 실행 및 DB 제약조건과 JPA Auditing 결과 확인
        // save(): Entity를 저장하도록 등록
        // flush(): 그 내용을 즉시 DB에 반영
        userRepository.saveAndFlush(user);

        // 만든 쿼리 메서드 실행
        Optional<User> foundUser = userRepository.findByEmailAndDeletedAtIsNull(email);

        // assert~: 테스트 코드에서 기대값을 검증하는 함수
        // true 참인지, equals 두 값이 동일한지, notnull 값이 있는지
        assertTrue(foundUser.isPresent()); // 활성 사용자가 정상적으로 조회되었는지 확인
        assertEquals(email, foundUser.get().getEmail()); // 조회된 이메일이 저장한 이메일과 동일한지 확인
        assertEquals(DefaultTalkLevel.SILENT, foundUser.get().getDefaultTalkLevel());
        assertTrue(userRepository.existsByEmailAndDeletedAtIsNull(email)); // 활성 사용자 존재 여부 조회가 true인지 확인
        assertNotNull(foundUser.get().getCreatedAt()); // 생성 시각이 자동 기록되었는지 확인
        assertNotNull(foundUser.get().getUpdatedAt()); // 수정 시각이 자동 기록되었는지 확인
    }

    @Test
    @DisplayName("대문자가 포함된 이메일 저장을 거부")
    void saveRejectsUppercaseEmail() {
        User user = new User(
                "Repository-" + UUID.randomUUID() + "@example.com",
                "encoded-password",
                "테스트사용자",
                DefaultTalkLevel.SILENT,
                Instant.now()
        );

        // assertThrows
        // 이 코드를 실행했을 때 특정 예외가 발생해야 테스트 성공이라고 검증 (JUnit 함수)
        // 예외 발생X 또는 다른 예외 발생 시 테스트 실패

        // 소문자로 정규화되지 않은 이메일의 DB 저장 실패 확인
        assertThrows(
                DataIntegrityViolationException.class,
                () -> userRepository.saveAndFlush(user)
        );
    }

    @Test
    @DisplayName("앞뒤 공백이 포함된 이메일 저장을 거부")
    void saveRejectsEmailWithSurroundingWhitespace() {
        User user = new User(
                " repository-" + UUID.randomUUID() + "@example.com ",
                "encoded-password",
                "테스트사용자",
                DefaultTalkLevel.SILENT,
                Instant.now()
        );

        // 앞뒤 공백이 제거되지 않은 이메일의 DB 저장 실패 확인
        assertThrows(
                DataIntegrityViolationException.class,
                () -> userRepository.saveAndFlush(user)
        );
    }
}
