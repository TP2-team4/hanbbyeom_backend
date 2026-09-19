package com.team4.hanbbyeom.feedback.repository;

import com.team4.hanbbyeom.feedback.domain.ActivityReview;
import com.team4.hanbbyeom.matching.domain.ActivityMatch;
import com.team4.hanbbyeom.matching.domain.AcceptStatus;
import com.team4.hanbbyeom.matching.domain.MatchParticipant;
import com.team4.hanbbyeom.matching.domain.MatchRequest;
import com.team4.hanbbyeom.matching.domain.TalkLevel;
import com.team4.hanbbyeom.matching.repository.ActivityMatchRepository;
import com.team4.hanbbyeom.matching.repository.MatchParticipantRepository;
import com.team4.hanbbyeom.matching.repository.MatchRequestRepository;
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
    private MatchRequestRepository matchRequestRepository;

    @Autowired
    private MatchParticipantRepository matchParticipantRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long reviewerUserId;
    private Long revieweeUserId;
    private Long activityMatchId;

    @BeforeEach
    void setUp() {
        reviewerUserId = insertTestUser("reviewer");
        revieweeUserId = insertTestUser("reviewee");

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
                        OffsetDateTime.now().plusMinutes(10),
                        OffsetDateTime.now()
                )
        );
        activityMatchId = savedMatch.getId();

        // fk_activity_review_reviewer_participant / reviewee_participant 제약을 만족시키려면
        // 두 사용자가 이 매칭의 실제 match_participant여야 함. match_participant는 다시
        // match_request(id, user_id) 복합 FK가 있어서, 각자 본인 소유의 match_request부터 만든다.
        Long reviewerRequestId = matchRequestRepository.save(
                new MatchRequest(reviewerUserId, OffsetDateTime.now().plusMinutes(20),
                        TalkLevel.SILENT, OffsetDateTime.now().plusMinutes(15))
        ).getId();
        Long revieweeRequestId = matchRequestRepository.save(
                new MatchRequest(revieweeUserId, OffsetDateTime.now().plusMinutes(20),
                        TalkLevel.SILENT, OffsetDateTime.now().plusMinutes(15))
        ).getId();

        matchParticipantRepository.save(
                new MatchParticipant(activityMatchId, reviewerRequestId, reviewerUserId, "A", AcceptStatus.ACCEPTED));
        matchParticipantRepository.save(
                new MatchParticipant(activityMatchId, revieweeRequestId, revieweeUserId, "B", AcceptStatus.ACCEPTED));
    }

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