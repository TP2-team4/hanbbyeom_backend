package com.team4.hanbbyeom.matching;

import com.team4.hanbbyeom.matching.domain.MatchRequest;
import com.team4.hanbbyeom.matching.domain.TalkLevel;
import com.team4.hanbbyeom.matching.repository.MatchRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional // 테스트가 끝나면 저장한 데이터를 자동으로 롤백해서 DB를 깨끗하게 유지
class MatchRequestRepositoryTest {

    @Autowired
    private MatchRequestRepository matchRequestRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long testUserId;

    @BeforeEach
    void 테스트용_유저를_미리_만들어둔다() {
        // users 테이블의 CHECK 제약(chk_users_account_lifecycle)이 활성 유저는
        // email/password_hash/nickname/email_verified_at을 전부 요구하므로 다 채워서 넣는다.
        testUserId = jdbcTemplate.queryForObject(
                """
                INSERT INTO users (email, password_hash, nickname, email_verified_at)
                VALUES ('test-user@example.com', 'dummy-hash', '테스트유저', now())
                RETURNING id
                """,
                Long.class
        );
    }

    @Test
    void 저장하고_아이디로_다시_조회할_수_있다() {
        // given
        MatchRequest request = new MatchRequest(
                testUserId,                            // 방금 만든 진짜 유저의 ID 사용
                OffsetDateTime.now().plusDays(1),      // scheduledAt
                TalkLevel.SILENT,
                OffsetDateTime.now().plusHours(12)     // searchExpiresAt
        );

        // when
        MatchRequest saved = matchRequestRepository.save(request);
        MatchRequest found = matchRequestRepository.findById(saved.getId()).orElseThrow();

        // then
        assertThat(found.getTalkLevel()).isEqualTo(TalkLevel.SILENT);
        assertThat(found.getUserId()).isEqualTo(testUserId);
    }
}