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
@Transactional
class MatchRequestRepositoryTest {

    @Autowired
    private MatchRequestRepository matchRequestRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long testUserId;
    private Long testRunMatchConditionId;

    @BeforeEach
    void 테스트용_유저와_코스조건을_미리_만들어둔다() {
        testUserId = jdbcTemplate.queryForObject(
                """
                INSERT INTO users (email, password_hash, nickname, email_verified_at)
                VALUES ('test-user@example.com', 'dummy-hash', '테스트유저', now())
                RETURNING id
                """,
                Long.class
        );

        testRunMatchConditionId = jdbcTemplate.queryForObject(
                """
                INSERT INTO run_match_condition (course_name, distance_meters, pace_min_sec, pace_max_sec, meeting_point, region)
                VALUES ('뚝섬 한강공원', 8000, 360, 400, '뚝섬유원지역 3번 출구', '뚝섬 한강공원')
                RETURNING id
                """,
                Long.class
        );
    }

    @Test
    void 저장하고_아이디로_다시_조회할_수_있다() {
        MatchRequest request = new MatchRequest(
                testUserId,
                testRunMatchConditionId,
                OffsetDateTime.now().plusDays(1),
                TalkLevel.SILENT,
                OffsetDateTime.now().plusHours(12)
        );

        MatchRequest saved = matchRequestRepository.save(request);
        MatchRequest found = matchRequestRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getTalkLevel()).isEqualTo(TalkLevel.SILENT);
        assertThat(found.getUserId()).isEqualTo(testUserId);
    }
}