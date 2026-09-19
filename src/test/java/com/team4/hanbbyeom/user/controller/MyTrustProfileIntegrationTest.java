package com.team4.hanbbyeom.user.controller;

import com.team4.hanbbyeom.global.security.jwt.JwtTokenProvider;
import com.team4.hanbbyeom.user.domain.DefaultTalkLevel;
import com.team4.hanbbyeom.user.domain.User;
import com.team4.hanbbyeom.user.repository.UserRepository;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 내 신뢰도 프로필 조회(GET /api/users/me/trust-profile) API를 실제 JWT 인증·Controller·
// Service·DB 흐름으로 검증. 조회 로직 자체는 TrustProfileLookupService가 이미 갖고 있어서
// (호스트/신청자 프로필 조회에서 검증된 로직), 여기서는 "본인 조회 경로"만 확인하면 된다.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MyTrustProfileIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JwtTokenProvider jwtTokenProvider;

    private User createUser() {
        return userRepository.saveAndFlush(new User(
                "trust-profile-" + UUID.randomUUID() + "@example.com",
                "encoded-password",
                "테스트사용자",
                DefaultTalkLevel.SILENT,
                Instant.now()
        ));
    }

    private String bearerToken(Long userId) {
        return "Bearer " + jwtTokenProvider.createAccessToken(userId);
    }

    @Test
    @DisplayName("활동 이력이 없는 신규 유저는 기본값(0, null)이 반환된다")
    void 신규_유저는_기본값이_반환된다() throws Exception {
        User user = createUser();

        mockMvc.perform(get("/api/users/me/trust-profile")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(user.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.averageRating").doesNotExist())
                .andExpect(jsonPath("$.reviewCount").value(0))
                .andExpect(jsonPath("$.completedCount").value(0))
                .andExpect(jsonPath("$.noShowReportCount").value(0));
    }

    @Test
    @DisplayName("활동 이력이 있는 유저는 실제 신뢰도 값이 반환된다")
    void 활동_이력이_있으면_실제_값이_반환된다() throws Exception {
        User user = createUser();
        jdbcTemplate.update(
                """
                INSERT INTO trust_profile (user_id, average_rating, review_count, completed_activity_count, no_show_report_count)
                VALUES (?, ?, ?, ?, ?)
                """,
                user.getId(), 4.9, 12, 12, 0
        );

        mockMvc.perform(get("/api/users/me/trust-profile")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(user.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.averageRating").value(4.9))
                .andExpect(jsonPath("$.completedCount").value(12));
    }

    @Test
    @DisplayName("인증 없이 조회하면 401이 반환된다")
    void 인증_없이_조회하면_401을_반환한다() throws Exception {
        mockMvc.perform(get("/api/users/me/trust-profile"))
                .andExpect(status().isUnauthorized());
    }
}