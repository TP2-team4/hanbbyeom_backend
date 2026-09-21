package com.team4.hanbbyeom.matching;

import com.team4.hanbbyeom.matching.domain.TalkLevel;
import com.team4.hanbbyeom.matching.dto.MatchRequestUpdateRequest;
import com.team4.hanbbyeom.matching.exception.MatchRequestNotSearchingException;
import com.team4.hanbbyeom.matching.service.MatchRequestCommandService;
import com.team4.hanbbyeom.run.dto.RunConditionUpdateRequest;
import com.team4.hanbbyeom.run.service.RunConditionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 모집글 수정(PATCH /api/matching/requests/{id})·러닝 조건 수정(PATCH /api/run/conditions/{id})·러닝 조건 삭제
// (DELETE /api/run/conditions/{id}, 게시글을 CANCELLED로 만든다)가 matching_mutex 락으로 신청·취소 같은 다른 상태 전이와
// 직렬화되는지 검증한다.
//
// 락이 없으면 이 경로들은 게시글을 읽은 뒤 apply()가 게시글을 PENDING_CONFIRMATION으로 바꿔 커밋해도 낡은 SEARCHING으로
// 검사를 통과하고, 커밋 시점에 게시글의 모든 컬럼을 UPDATE(엔티티에 @DynamicUpdate/@Version이 없다)해 status를 되돌리거나
// (수정), "게시글 CANCELLED + 매칭 PROPOSED" 불일치를 만든다(삭제). 이런 경합은 확률적이라 반복으로는 잡기 어려우므로,
// "다른 상태 전이가 락을 쥔 채 커밋하지 않고 있는 상황"을 직접 만들어 결정적으로 확인한다:
//   (1) 락이 풀릴 때까지 편집이 끝나지 않고 기다리는가  (2) 풀린 뒤에는 커밋된 상태(신청 대기)를 보고 거부하는가
//
// 각 스레드가 서로 다른 커넥션을 써야 하므로 클래스 레벨 @Transactional을 쓰지 않고, 만든 데이터를 직접 지운다.
@SpringBootTest
class MatchingEditPathsLockTest {

    private static final long WAIT_MILLIS = 1000; // 이 시간 동안 끝나지 않으면 "락을 기다리는 중"으로 본다

    @Autowired private MatchRequestCommandService matchRequestCommandService;
    @Autowired private RunConditionService runConditionService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private Long userId;
    private Long postId;
    private Long courseId;

    @BeforeEach
    void setUp() {
        userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, nickname, email_verified_at) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class, "edit-lock-" + UUID.randomUUID() + "@example.com", "dummy-hash", "호스트", OffsetDateTime.now());
        courseId = jdbcTemplate.queryForObject(
                "SELECT id FROM running_course WHERE name = ? LIMIT 1", Long.class, "뚝섬 한강공원");
        OffsetDateTime start = OffsetDateTime.now().plusDays(3);
        postId = jdbcTemplate.queryForObject(
                """
                INSERT INTO match_request (user_id, status, scheduled_at, talk_level, search_expires_at)
                VALUES (?, 'SEARCHING', ?, 'LIGHT_CHAT', ?)
                RETURNING id
                """,
                Long.class, userId, start, start.minusHours(1));
        jdbcTemplate.update(
                """
                INSERT INTO run_match_condition
                    (match_request_id, course_id, meeting_point, distance_min_meters, distance_max_meters, pace_min_sec, pace_max_sec)
                VALUES (?, ?, '뚝섬유원지역 3번 출구', 5000, 8000, 360, 400)
                """,
                postId, courseId);
    }

    @AfterEach
    void cleanUp() throws InterruptedException {
        executor.shutdownNow();
        executor.awaitTermination(5, TimeUnit.SECONDS);
        jdbcTemplate.update("DELETE FROM run_match_condition WHERE match_request_id = ?", postId);
        jdbcTemplate.update("DELETE FROM match_request WHERE id = ?", postId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
    }

    // 신청(apply) 같은 다른 상태 전이가 이미 락을 잡고 진행 중인 상황: 락을 잡고 게시글을 PENDING_CONFIRMATION으로 바꾼 채
    // 커밋하지 않고 기다린다. commit()을 부르면 커밋하며 락이 풀린다.
    private final class InFlightTransition {
        private final CountDownLatch locked = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final Future<?> finished;

        InFlightTransition() throws InterruptedException {
            finished = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbcTemplate.queryForObject("SELECT id FROM matching_mutex WHERE id = 1 FOR UPDATE", Long.class);
                jdbcTemplate.update("UPDATE match_request SET status = 'PENDING_CONFIRMATION' WHERE id = ?", postId);
                locked.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).as("다른 상태 전이가 락을 잡았어야 한다").isTrue();
        }

        void commit() throws Exception {
            release.countDown();
            finished.get(5, TimeUnit.SECONDS);
        }
    }

    // 다른 상태 전이가 락을 쥐고 있는 동안 편집을 시작하고, 락이 풀린 뒤 편집이 던진 예외(없으면 null)를 돌려준다
    private Throwable editWhileTransitionInFlight(Callable<Void> edit) throws Exception {
        InFlightTransition inFlight = new InFlightTransition();
        Callable<Throwable> task = () -> {
            try {
                edit.call();
                return null;
            } catch (Throwable t) {
                return t;
            }
        };
        Future<Throwable> result = executor.submit(task);
        try {
            // 락 없이 진행하면 바로 끝나 이 검증이 실패한다
            assertThatThrownBy(() -> result.get(WAIT_MILLIS, TimeUnit.MILLISECONDS))
                    .as("락을 쥔 다른 상태 전이가 끝나기 전에는 편집이 진행되면 안 된다")
                    .isInstanceOf(TimeoutException.class);
        } finally {
            inFlight.commit();
        }
        return result.get(5, TimeUnit.SECONDS);
    }

    private String postStatus() {
        return jdbcTemplate.queryForObject("SELECT status FROM match_request WHERE id = ?", String.class, postId);
    }

    @Test
    @DisplayName("모집글 수정은 락이 풀릴 때까지 기다렸다가, 커밋된 신청 대기 상태를 보고 거부하며 일정과 상태를 바꾸지 않는다")
    void 모집글_수정은_락을_기다린_뒤_커밋된_상태로_거부한다() throws Exception {
        Double scheduledBefore = jdbcTemplate.queryForObject(
                "SELECT EXTRACT(EPOCH FROM scheduled_at)::float8 FROM match_request WHERE id = ?", Double.class, postId);

        Throwable result = editWhileTransitionInFlight(() -> {
            matchRequestCommandService.update(userId, postId,
                    new MatchRequestUpdateRequest(OffsetDateTime.now().plusDays(5), TalkLevel.SILENT));
            return null;
        });

        assertThat(result).isInstanceOf(MatchRequestNotSearchingException.class);
        assertThat(postStatus()).isEqualTo("PENDING_CONFIRMATION"); // 낡은 SEARCHING으로 되돌아가지 않는다
        assertThat(jdbcTemplate.queryForObject(
                "SELECT EXTRACT(EPOCH FROM scheduled_at)::float8 FROM match_request WHERE id = ?", Double.class, postId))
                .isEqualTo(scheduledBefore);
    }

    @Test
    @DisplayName("러닝 조건 수정은 락이 풀릴 때까지 기다렸다가, 커밋된 신청 대기 상태를 보고 거부하며 조건을 바꾸지 않는다")
    void 러닝_조건_수정은_락을_기다린_뒤_커밋된_상태로_거부한다() throws Exception {
        Throwable result = editWhileTransitionInFlight(() -> {
            runConditionService.update(userId, postId,
                    new RunConditionUpdateRequest(courseId, "변경된 만나는 곳", 6000, 9000, 350, 420));
            return null;
        });

        assertThat(result).isInstanceOf(MatchRequestNotSearchingException.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT meeting_point FROM run_match_condition WHERE match_request_id = ?", String.class, postId))
                .isEqualTo("뚝섬유원지역 3번 출구");
    }

    @Test
    @DisplayName("러닝 조건 삭제는 락이 풀릴 때까지 기다렸다가, 커밋된 신청 대기 상태를 보고 거부하며 게시글을 취소하지 않는다")
    void 러닝_조건_삭제는_락을_기다린_뒤_커밋된_상태로_거부한다() throws Exception {
        Throwable result = editWhileTransitionInFlight(() -> {
            runConditionService.delete(userId, postId);
            return null;
        });

        assertThat(result).isInstanceOf(MatchRequestNotSearchingException.class);
        // 게시글이 CANCELLED로 바뀌었다면 신청이 걸린 매칭과 어긋난다
        assertThat(postStatus()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM run_match_condition WHERE match_request_id = ?", Integer.class, postId)).isEqualTo(1);
    }
}
