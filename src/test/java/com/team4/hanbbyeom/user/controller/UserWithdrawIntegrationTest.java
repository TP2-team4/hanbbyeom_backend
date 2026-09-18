package com.team4.hanbbyeom.user.controller;

import com.team4.hanbbyeom.global.security.jwt.JwtTokenProvider;
import com.team4.hanbbyeom.user.domain.DefaultTalkLevel;
import com.team4.hanbbyeom.user.domain.User;
import com.team4.hanbbyeom.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 회원 탈퇴(POST /api/users/me/withdraw) API를 실제 JWT 인증·Controller·Service·DB 흐름으로 검증
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserWithdrawIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @PersistenceContext private EntityManager entityManager;

    private User createUser(String email) {
        return userRepository.saveAndFlush(new User(
                email, "encoded-password", "테스트사용자", DefaultTalkLevel.SILENT, Instant.now()
        ));
    }

    private String bearerToken(Long userId) {
        return "Bearer " + jwtTokenProvider.createAccessToken(userId);
    }

    @Test
    @DisplayName("탈퇴하면 개인정보가 전부 NULL로 지워지고 탈퇴 시각이 기록된다")
    void 탈퇴하면_개인정보가_모두_제거된다() throws Exception {
        User user = createUser("withdraw-" + UUID.randomUUID() + "@example.com");

        mockMvc.perform(post("/api/users/me/withdraw")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(user.getId())))
                .andExpect(status().isNoContent());
        entityManager.flush();

        User withdrawn = userRepository.findById(user.getId()).orElseThrow();
        assertThat(withdrawn.getEmail()).isNull();
        assertThat(withdrawn.getNickname()).isNull();
        assertThat(withdrawn.getDefaultTalkLevel()).isNull();
        assertThat(withdrawn.getDeletedAt()).isNotNull();
    }

    @Test
    @DisplayName("탈퇴 후 같은 이메일로 다시 가입할 수 있다")
    void 탈퇴_후_같은_이메일로_재가입할_수_있다() throws Exception {
        String email = "rejoin-" + UUID.randomUUID() + "@example.com";
        User user = createUser(email);

        mockMvc.perform(post("/api/users/me/withdraw")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(user.getId())))
                .andExpect(status().isNoContent());
        // withdraw()의 email UPDATE가 아직 DB에 반영 안 된 상태일 수 있는데, User가 IDENTITY
        // 전략이라 바로 아래 saveAndFlush()의 INSERT가 그 UPDATE보다 먼저 실행돼버릴 수 있다
        // (실제 운영에서는 요청이 끝나며 트랜잭션이 커밋되니 문제없음 — 테스트에서만 필요).
        entityManager.flush();

        // UNIQUE(email) 제약은 NULL끼리는 중복으로 안 치므로, 탈퇴한 계정과 같은 이메일로
        // 새 활성 계정을 만들어도 제약 위반이 나면 안 된다.
        User newUser = userRepository.saveAndFlush(new User(
                email, "encoded-password", "새사용자", DefaultTalkLevel.LIGHT_CHAT, Instant.now()
        ));

        assertThat(newUser.getId()).isNotEqualTo(user.getId());
    }

    @Test
    @DisplayName("탈퇴 후 기존 access token으로 요청하면 401이 반환된다")
    void 탈퇴_후_기존_토큰으로_요청하면_401을_반환한다() throws Exception {
        User user = createUser("token-" + UUID.randomUUID() + "@example.com");
        String token = bearerToken(user.getId());

        mockMvc.perform(post("/api/users/me/withdraw")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("탈퇴하면 남아 있던 이메일 인증 기록도 함께 삭제된다")
    void 탈퇴하면_이메일_인증_기록도_삭제된다() throws Exception {
        String email = "verified-" + UUID.randomUUID() + "@example.com";
        User user = createUser(email);
        jdbcTemplate.update(
                """
                INSERT INTO email_verifications (email, purpose, code_hash, expires_at, verified_at)
                VALUES (?, 'SIGNUP', 'dummy-hash', now() + interval '1 hour', now())
                """,
                email
        );

        mockMvc.perform(post("/api/users/me/withdraw")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(user.getId())))
                .andExpect(status().isNoContent());
        // deleteByEmail()의 삭제가 아직 DB에 반영 안 된 상태일 수 있는데, 바로 아래 검증은
        // raw JDBC라 Hibernate 세션을 안 거쳐서 flush 없이는 옛날 값을 보게 된다.
        entityManager.flush();

        Integer remaining = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM email_verifications WHERE email = ?", Integer.class, email);
        assertThat(remaining).isZero();
    }
}