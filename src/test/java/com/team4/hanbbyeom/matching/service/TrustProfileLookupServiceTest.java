package com.team4.hanbbyeom.matching.service;

import com.team4.hanbbyeom.matching.dto.TrustProfileResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class TrustProfileLookupServiceTest {

    @Autowired
    private TrustProfileLookupService trustProfileLookupService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long testUserId;

    @BeforeEach
    void setUp() {
        testUserId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, nickname, email_verified_at) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class,
                "trust-test-" + System.nanoTime() + "@example.com", "dummy-hash", "신뢰유저", java.time.OffsetDateTime.now()
        );
    }

    @Test
    void trust_profile_행이_있으면_실제_값을_반환한다() {
        jdbcTemplate.update(
                "INSERT INTO trust_profile (user_id, average_rating, completed_activity_count, review_count, no_show_report_count) VALUES (?, ?, ?, ?, ?)",
                testUserId, 4.8, 31, 12, 0
        );

        TrustProfileResponse response = trustProfileLookupService.lookup(testUserId);

        assertThat(response.averageRating()).isEqualTo(4.8);
        assertThat(response.completedCount()).isEqualTo(31);
        assertThat(response.reviewCount()).isEqualTo(12);
    }

    @Test
    void trust_profile_행이_없으면_기본값을_반환한다() {
        TrustProfileResponse response = trustProfileLookupService.lookup(testUserId);

        assertThat(response.averageRating()).isNull();
        assertThat(response.completedCount()).isZero();
        assertThat(response.reviewCount()).isZero();
        assertThat(response.noShowReportCount()).isZero();
    }
}