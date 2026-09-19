package com.team4.hanbbyeom.feedback;

import com.team4.hanbbyeom.feedback.domain.NoShowReason;
import com.team4.hanbbyeom.feedback.dto.NoShowReportCreateRequest;
import com.team4.hanbbyeom.feedback.dto.ReviewCreateRequest;
import com.team4.hanbbyeom.feedback.exception.FeedbackAlreadySubmittedException;
import com.team4.hanbbyeom.feedback.service.ActivityFeedbackService;
import com.team4.hanbbyeom.matching.domain.AcceptStatus;
import com.team4.hanbbyeom.matching.domain.ActivityMatch;
import com.team4.hanbbyeom.matching.domain.MatchParticipant;
import com.team4.hanbbyeom.matching.domain.MatchRequest;
import com.team4.hanbbyeom.matching.domain.TalkLevel;
import com.team4.hanbbyeom.matching.repository.ActivityMatchRepository;
import com.team4.hanbbyeom.matching.repository.MatchParticipantRepository;
import com.team4.hanbbyeom.matching.repository.MatchRequestRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

// 같은 사용자가 같은 활동에 대해 "후기"와 "노쇼 신고"를 거의 동시에 제출하면, activity_match
// 행 비관적 락(ActivityMatchRepository.findByIdForUpdate, SELECT ... FOR UPDATE) 덕분에
// 둘 중 하나만 성공하고 나머지는 FeedbackAlreadySubmittedException으로 거부되는지 검증한다.
// activity_review와 no_show_report가 서로 다른 테이블이라, 이 락이 없으면 각 테이블의
// existsBy 체크가 서로의 커밋을 못 보고 통과해 둘 다 저장될 수 있었다 (PR #83 리뷰로 발견).
//
// MatchingConcurrentApplyTest와 동일한 이유로 클래스 레벨 @Transactional을 안 쓴다 — 진짜
// 동시성을 재현하려면 각 스레드가 서로 다른 커넥션/트랜잭션을 써야 하는데, 테스트 전체를
// 하나의 트랜잭션(하나의 커넥션)으로 묶으면 아직 커밋 안 된 픽스처를 다른 스레드가 못 본다.
// 대신 @AfterEach에서 생성한 데이터를 직접 지운다.
@SpringBootTest
class ActivityFeedbackConcurrentSubmitTest {

    @Autowired private ActivityFeedbackService activityFeedbackService;
    @Autowired private ActivityMatchRepository activityMatchRepository;
    @Autowired private MatchRequestRepository matchRequestRepository;
    @Autowired private MatchParticipantRepository matchParticipantRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long userAId;
    private Long userBId;
    private Long activityMatchId;

    private Long createUser(String label) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO users (email, password_hash, nickname, email_verified_at)
                VALUES (?, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                "concurrent-" + UUID.randomUUID() + "@example.com", "dummy-hash", label, OffsetDateTime.now()
        );
    }

    @AfterEach
    void cleanUp() {
        // 여긴 @Transactional 롤백이 없으므로, 커밋된 테스트 데이터를 자식 테이블부터 직접 지운다.
        if (activityMatchId != null) {
            jdbcTemplate.update("DELETE FROM activity_review WHERE activity_match_id = ?", activityMatchId);
            jdbcTemplate.update("DELETE FROM no_show_report WHERE activity_match_id = ?", activityMatchId);
            jdbcTemplate.update("DELETE FROM match_participant WHERE activity_match_id = ?", activityMatchId);
            jdbcTemplate.update("DELETE FROM activity_match WHERE id = ?", activityMatchId);
        }
        if (userAId != null && userBId != null) {
            jdbcTemplate.update("DELETE FROM match_request WHERE user_id IN (?, ?)", userAId, userBId);
            jdbcTemplate.update("DELETE FROM trust_profile WHERE user_id IN (?, ?)", userAId, userBId);
            jdbcTemplate.update("DELETE FROM users WHERE id IN (?, ?)", userAId, userBId);
        }
    }

    @Test
    @DisplayName("같은 사용자가 같은 활동에 후기와 노쇼 신고를 동시에 제출하면 하나만 성공한다")
    void 후기와_노쇼_신고_동시_제출_경합() throws Exception {
        userAId = createUser("동시성-A");
        userBId = createUser("동시성-B");
        activityMatchId = createEndedActivityMatch(userAId, userBId);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Object> reviewResult = new AtomicReference<>();
        AtomicReference<Object> noShowResult = new AtomicReference<>();

        Callable<Void> reviewTask = () -> {
            ready.countDown();
            start.await();
            try {
                activityFeedbackService.submitReview(activityMatchId, userAId,
                        new ReviewCreateRequest(5, TalkLevel.SILENT, null));
                reviewResult.set("OK");
            } catch (Exception e) {
                reviewResult.set(e);
            }
            return null;
        };
        Callable<Void> noShowTask = () -> {
            ready.countDown();
            start.await();
            try {
                activityFeedbackService.submitNoShowReport(activityMatchId, userAId,
                        new NoShowReportCreateRequest(NoShowReason.NOT_SHOWED_UP, null));
                noShowResult.set("OK");
            } catch (Exception e) {
                noShowResult.set(e);
            }
            return null;
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var future1 = executor.submit(reviewTask);
            var future2 = executor.submit(noShowTask);

            // MatchingConcurrentApplyTest와 동일하게, 두 스레드 모두 제출 직전(start 대기)까지
            // 진입한 걸 확인한 뒤 동시에 출발시킨다.
            assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            future1.get(5, TimeUnit.SECONDS);
            future2.get(5, TimeUnit.SECONDS);
        } finally {
            // 위에서 어떤 이유로든 일찍 빠져나가도, 대기 중인 스레드를 먼저 깨우고
            // shutdownNow()로 강제 종료해야 테스트 JVM이 멈추지 않는다.
            start.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }

        List<Object> results = List.of(reviewResult.get(), noShowResult.get());
        long successCount = results.stream().filter(r -> "OK".equals(r)).count();
        long conflictCount = results.stream().filter(r -> r instanceof FeedbackAlreadySubmittedException).count();

        // 정확히 하나만 성공하고, 나머지 하나는 FeedbackAlreadySubmittedException으로 거부돼야 한다
        assertThat(successCount).isEqualTo(1);
        assertThat(conflictCount).isEqualTo(1);

        // 성공한 쪽만 trust_profile에 반영되어야 한다 — 락이 없었다면 둘 다 반영될 수 있었다
        var profile = jdbcTemplate.queryForMap(
                "SELECT review_count, no_show_report_count FROM trust_profile WHERE user_id = ?", userBId);
        boolean reviewWon = "OK".equals(reviewResult.get());
        assertThat(profile.get("review_count")).isEqualTo(reviewWon ? 1 : 0);
        assertThat(profile.get("no_show_report_count")).isEqualTo(reviewWon ? 0 : 1);
    }

    private Long createEndedActivityMatch(Long userA, Long userB) {
        OffsetDateTime base = OffsetDateTime.now().minusHours(2);
        ActivityMatch activityMatch = new ActivityMatch(
                base.plusMinutes(20), base.plusMinutes(30), TalkLevel.SILENT,
                "뚝섬 한강공원", "뚝섬 한강공원 코스", 5000, 12000,
                "뚝섬유원지역 3번 출구", 360, 400,
                base.plusMinutes(10), base
        );
        activityMatch.confirm("123456");
        activityMatch.end();
        Long id = activityMatchRepository.save(activityMatch).getId();

        Long requestAId = matchRequestRepository.save(
                new MatchRequest(userA, OffsetDateTime.now().plusMinutes(20), TalkLevel.SILENT, OffsetDateTime.now().plusMinutes(15))
        ).getId();
        Long requestBId = matchRequestRepository.save(
                new MatchRequest(userB, OffsetDateTime.now().plusMinutes(20), TalkLevel.SILENT, OffsetDateTime.now().plusMinutes(15))
        ).getId();

        matchParticipantRepository.save(new MatchParticipant(id, requestAId, userA, "A", AcceptStatus.ACCEPTED));
        matchParticipantRepository.save(new MatchParticipant(id, requestBId, userB, "B", AcceptStatus.ACCEPTED));

        return id;
    }
}
