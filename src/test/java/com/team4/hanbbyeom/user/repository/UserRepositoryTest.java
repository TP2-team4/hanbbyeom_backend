package com.team4.hanbbyeom.user.repository;

import com.team4.hanbbyeom.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// User Entity와 이메일 조회 쿼리 메서드의 PostgreSQL 연동 검증

@SpringBootTest // Spring Boot 애플리케이션 전체 컨텍스트를 띄워서 테스트하는 어노테이션
@Transactional // 테스트 메서드가 끝나면 DB 변경 내용을 롤백
class UserRepositoryTest {

    @Autowired // Spring이 필요한 객체를 자동으로 주입해주는 어노테이션
    private UserRepository userRepository;

    @Test // 테스트 메서드
    @DisplayName("이메일로 탈퇴하지 않은 사용자를 조회한다") // 테스트 결과 화면에서 읽기 좋은 이름으로 보여줌
    void findByEmailAndDeletedAtIsNullReturnsUser() {
        // 기존 데이터와 이메일이 겹치지 않는 테스트 사용자 생성
        String email = "repository-" + UUID.randomUUID() + "@example.com";
        User user = new User(
                email,
                "encoded-password", // 테스트 목적이 "비밀번호 암호화 검증"이 아니라 Repository 저장/조회 검증
                "테스트사용자",
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
        assertTrue(userRepository.existsByEmailAndDeletedAtIsNull(email)); // 활성 사용자 존재 여부 조회가 true인지 확인
        assertNotNull(foundUser.get().getCreatedAt()); // 생성 시각이 자동 기록되었는지 확인
        assertNotNull(foundUser.get().getUpdatedAt()); // 수정 시각이 자동 기록되었는지 확인
    }
}
