package com.team4.hanbbyeom.feedback.repository;

import com.team4.hanbbyeom.feedback.domain.ActivityReview;
import com.team4.hanbbyeom.matching.domain.ActivityMatch;
import com.team4.hanbbyeom.matching.domain.TalkLevel;
import com.team4.hanbbyeom.matching.repository.ActivityMatchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
public class ActivityReviewRepositoryTest {

    @Autowired
    private ActivityReviewRepository activityReviewRepository;

    @Autowired
    private ActivityMatchRepository activityMatchRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long reviewerUserId;
    private Long revieweeUserId;
    private Long activityMatchId;

    @BeforeEach
    void setUp() {
        reviewerUserId = insertTestUser("reviewer");
        revieweeUserId = insertTestUser("reviewee");

        // activity_match의 chk_activity_match_time 제약: created_at(=지금) < decision_expires_at
        // < scheduled_at < scheduled_end_at 순서를 반드시 지켜야 해서, 전부 "지금보다 미래" 값으로 넣음
        // (우리 목적은 "매칭이 실제로 끝났는지"가 아니라 FK가 가리킬 유효한 행 하나 만드는 것뿐이라 상관없음)
        ActivityMatch savedMatch = activityMatchRepository.save(
                new ActivityMatch(
                        OffsetDateTime.now().plusMinutes(20),
                        OffsetDateTime.now().plusMinutes(30),
                        TalkLevel.SILENT,
                        "뚝섬 한강공원",
                        "뚝섬 한강공원 코스",
                        5000,
                        12000,
                        "뚝섬유원지역 3번 출구",
                        360,
                        400,
                        OffsetDateTime.now().plusMinutes(10)
                )
        );
        activityMatchId = savedMatch.getId();
    }

    // users 테이블 CHECK 제약(chk_users_account_lifecycle) 때문에 필수 필드를 다 채워서 넣음
    // — RunMatchConditionRepositoryTest의 setUp()과 동일한 패턴
    private Long insertTestUser(String label) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO users (email, password_hash, nickname, email_verified_at)
                VALUES (?, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                "review-test-" + label + "-" + System.nanoTime() + "@example.com",
                "dummy-hash",
                label + "테스트유저",
                OffsetDateTime.now()
        );
    }

    @Test
    public void 저장하고_중복_여부를_확인할_수_있다() {
        assertThat(activityReviewRepository.existsByActivityMatchIdAndReviewerUserId(
                activityMatchId, reviewerUserId)).isFalse();

        activityReviewRepository.save(new ActivityReview(
                activityMatchId, reviewerUserId, revieweeUserId,
                5, TalkLevel.LIGHT_CHAT, "좋았어요"
        ));

        assertThat(activityReviewRepository.existsByActivityMatchIdAndReviewerUserId(
                activityMatchId, reviewerUserId)).isTrue();
    }

    @Test
    public void 배치로_후기_작성한_활동_id만_골라낼_수_있다() {
        activityReviewRepository.save(new ActivityReview(
                activityMatchId, reviewerUserId, revieweeUserId,
                4, TalkLevel.SILENT, null
        ));

        List<Long> reviewed = activityReviewRepository.findActivityMatchIdByReviewerUserIdAndActivityMatchIdIn(
                reviewerUserId, List.of(activityMatchId, 999_999L)
        );

        assertThat(reviewed).containsExactly(activityMatchId);
    }
}