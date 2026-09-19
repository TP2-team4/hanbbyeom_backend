package com.team4.hanbbyeom.user.controller;

import com.team4.hanbbyeom.global.security.jwt.JwtTokenProvider;
import com.team4.hanbbyeom.matching.domain.MatchRequest;
import com.team4.hanbbyeom.matching.domain.MatchRequestStatus;
import com.team4.hanbbyeom.matching.domain.TalkLevel;
import com.team4.hanbbyeom.matching.repository.MatchRequestRepository;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 회원 탈퇴(POST /api/users/me/withdraw) API를 실제 JWT 인증·Controller·Service·DB 흐름으로 검증
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserWithdrawIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private MatchRequestRepository matchRequestRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private PasswordEncoder passwordEncoder;
    @PersistenceContext private EntityManager entityManager;

    private static final String PASSWORD = "password1234";

    // 비밀번호 재확인이 실제 BCrypt 비교로 이뤄지므로 해시를 진짜로 만들어 저장
    private User createUser(String email) {
        return userRepository.saveAndFlush(new User(
                email, passwordEncoder.encode(PASSWORD), "테스트사용자", DefaultTalkLevel.SILENT, Instant.now()
        ));
    }

    private String withdrawBody(String password) {
        return "{\"password\": \"" + password + "\"}";
    }

    private ResultActions withdraw(String token, String password) throws Exception {
        return mockMvc.perform(post("/api/users/me/withdraw")
                .header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(withdrawBody(password)));
    }

    private String bearerToken(Long userId) {
        return "Bearer " + jwtTokenProvider.createAccessToken(userId);
    }

    @Test
    @DisplayName("탈퇴하면 개인정보가 전부 NULL로 지워지고 탈퇴 시각이 기록된다")
    void 탈퇴하면_개인정보가_모두_제거된다() throws Exception {
        User user = createUser("withdraw-" + UUID.randomUUID() + "@example.com");

        withdraw(bearerToken(user.getId()), PASSWORD)
                .andExpect(status().isNoContent());
        entityManager.flush();

        User withdrawn = userRepository.findById(user.getId()).orElseThrow();
        assertThat(withdrawn.getEmail()).isNull();
        assertThat(withdrawn.getPasswordHash()).isNull();
        assertThat(withdrawn.getEmailVerifiedAt()).isNull();
        assertThat(withdrawn.getNickname()).isNull();
        assertThat(withdrawn.getDefaultTalkLevel()).isNull();
        assertThat(withdrawn.getDeletedAt()).isNotNull();
    }

    @Test
    @DisplayName("탈퇴 후 같은 이메일로 다시 가입할 수 있다")
    void 탈퇴_후_같은_이메일로_재가입할_수_있다() throws Exception {
        String email = "rejoin-" + UUID.randomUUID() + "@example.com";
        User user = createUser(email);

        withdraw(bearerToken(user.getId()), PASSWORD)
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

        withdraw(token, PASSWORD)
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

        withdraw(bearerToken(user.getId()), PASSWORD)
                .andExpect(status().isNoContent());
        // deleteByEmail()의 삭제가 아직 DB에 반영 안 된 상태일 수 있는데, 바로 아래 검증은
        // raw JDBC라 Hibernate 세션을 안 거쳐서 flush 없이는 옛날 값을 보게 된다.
        entityManager.flush();

        Integer remaining = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM email_verifications WHERE email = ?", Integer.class, email);
        assertThat(remaining).isZero();
    }

    @Test
    @DisplayName("비밀번호가 틀리면 403으로 거부하고 계정은 그대로 유지된다")
    void 비밀번호가_틀리면_탈퇴가_거부된다() throws Exception {
        User user = createUser("wrong-pw-" + UUID.randomUUID() + "@example.com");
        String token = bearerToken(user.getId());

        withdraw(token, "not-my-password")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("비밀번호가 올바르지 않습니다."));
        entityManager.flush();
        entityManager.clear();

        User unchanged = userRepository.findById(user.getId()).orElseThrow();
        assertThat(unchanged.getDeletedAt()).isNull();
        assertThat(unchanged.getEmail()).isEqualTo(user.getEmail());

        // 거부 이후에도 같은 토큰이 계속 유효해야 한다(401이 아니라 403인 이유)
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("비밀번호가 비어 있거나 바디가 없으면 400으로 거부하고 계정은 유지된다")
    void 비밀번호가_없으면_400을_반환한다() throws Exception {
        User user = createUser("blank-pw-" + UUID.randomUUID() + "@example.com");
        String token = bearerToken(user.getId());

        withdraw(token, "").andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/users/me/withdraw")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/users/me/withdraw")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isBadRequest());

        entityManager.flush();
        entityManager.clear();
        assertThat(userRepository.findById(user.getId()).orElseThrow().getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("토큰 없이 탈퇴를 요청하면 401이 반환된다")
    void 토큰_없이_탈퇴하면_401을_반환한다() throws Exception {
        mockMvc.perform(post("/api/users/me/withdraw")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withdrawBody(PASSWORD)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("다른 사용자의 비밀번호로는 탈퇴할 수 없다")
    void 다른_사용자의_비밀번호로는_탈퇴할_수_없다() throws Exception {
        User me = createUser("me-" + UUID.randomUUID() + "@example.com");
        User other = userRepository.saveAndFlush(new User(
                "other-" + UUID.randomUUID() + "@example.com",
                passwordEncoder.encode("other-password99"),
                "다른사용자", DefaultTalkLevel.SILENT, Instant.now()
        ));

        // 내 토큰으로 다른 사용자의 비밀번호를 보내도 거부되고, 다른 사용자 계정도 영향받지 않는다
        withdraw(bearerToken(me.getId()), "other-password99")
                .andExpect(status().isForbidden());
        entityManager.flush();
        entityManager.clear();

        assertThat(userRepository.findById(me.getId()).orElseThrow().getDeletedAt()).isNull();
        assertThat(userRepository.findById(other.getId()).orElseThrow().getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("탈퇴 후 같은 이메일·비밀번호로 로그인하면 실패한다")
    void 탈퇴_후_로그인하면_실패한다() throws Exception {
        String email = "login-" + UUID.randomUUID() + "@example.com";
        User user = createUser(email);

        withdraw(bearerToken(user.getId()), PASSWORD).andExpect(status().isNoContent());
        entityManager.flush();

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"" + email + "\", \"password\": \"" + PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    private MatchRequest createMatchRequest(Long userId, MatchRequestStatus status) {
        MatchRequest request = matchRequestRepository.save(new MatchRequest(
                userId, OffsetDateTime.now().plusHours(48), TalkLevel.LIGHT_CHAT,
                OffsetDateTime.now().plusHours(9)
        ));
        request.changeStatus(status);
        matchRequestRepository.flush();
        return request;
    }

    @Test
    @DisplayName("탈퇴하면 본인의 모집 중(SEARCHING) 게시글이 CANCELLED로 바뀐다")
    void 탈퇴하면_모집_중_게시글이_취소된다() throws Exception {
        User user = createUser("cancel-" + UUID.randomUUID() + "@example.com");
        MatchRequest request = createMatchRequest(user.getId(), MatchRequestStatus.SEARCHING);

        withdraw(bearerToken(user.getId()), PASSWORD).andExpect(status().isNoContent());
        entityManager.flush();
        entityManager.clear();

        assertThat(matchRequestRepository.findById(request.getId()).orElseThrow().getStatus())
                .isEqualTo(MatchRequestStatus.CANCELLED);
    }

    // 신청이 진행 중이거나 확정된 매칭은 상대방이 있어, 즉시 취소·통보할지 기한까지 기다릴지가
    // 제품 결정이라 이번 범위에서는 건드리지 않는다(별도 이슈). 그 경계를 테스트로 고정해 둔다.
    @Test
    @DisplayName("신청 대기(PENDING_CONFIRMATION)·확정(MATCHED) 게시글은 탈퇴 시 건드리지 않는다")
    void 탈퇴해도_진행_중인_매칭_게시글은_그대로다() throws Exception {
        User pendingUser = createUser("pending-" + UUID.randomUUID() + "@example.com");
        MatchRequest pending = createMatchRequest(pendingUser.getId(), MatchRequestStatus.PENDING_CONFIRMATION);
        User matchedUser = createUser("matched-" + UUID.randomUUID() + "@example.com");
        MatchRequest matched = createMatchRequest(matchedUser.getId(), MatchRequestStatus.MATCHED);

        withdraw(bearerToken(pendingUser.getId()), PASSWORD).andExpect(status().isNoContent());
        withdraw(bearerToken(matchedUser.getId()), PASSWORD).andExpect(status().isNoContent());
        entityManager.flush();
        entityManager.clear();

        assertThat(matchRequestRepository.findById(pending.getId()).orElseThrow().getStatus())
                .isEqualTo(MatchRequestStatus.PENDING_CONFIRMATION);
        assertThat(matchRequestRepository.findById(matched.getId()).orElseThrow().getStatus())
                .isEqualTo(MatchRequestStatus.MATCHED);
    }

    @Test
    @DisplayName("비밀번호가 틀려 탈퇴가 거부되면 모집 중 게시글도 취소되지 않는다")
    void 탈퇴가_거부되면_게시글도_유지된다() throws Exception {
        User user = createUser("keep-" + UUID.randomUUID() + "@example.com");
        MatchRequest request = createMatchRequest(user.getId(), MatchRequestStatus.SEARCHING);

        withdraw(bearerToken(user.getId()), "not-my-password").andExpect(status().isForbidden());
        entityManager.flush();
        entityManager.clear();

        assertThat(matchRequestRepository.findById(request.getId()).orElseThrow().getStatus())
                .isEqualTo(MatchRequestStatus.SEARCHING);
    }
}
