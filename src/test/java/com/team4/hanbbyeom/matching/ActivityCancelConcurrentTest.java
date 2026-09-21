package com.team4.hanbbyeom.matching;

import com.team4.hanbbyeom.matching.exception.MatchRequestNotSearchingException;
import com.team4.hanbbyeom.matching.service.ActivityCancelService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

// 호스트와 신청자가 같은 확정 활동을 "동시에" 취소했을 때 matching_mutex 락 덕분에 한쪽만 성공하고, 매칭·참가자·게시글·채팅이
// 어긋나지 않는지 검증한다(이슈 #109). 락이 없으면 둘 다 CONFIRMED로 읽고 통과해 취소 메시지가 두 번 남고, 게시글은
// "취소한 사람은 CANCELLED, 상대는 SEARCHING"이라는 규칙이 뒤섞인다.
//
// MatchingConcurrentApplyTest와 같은 이유로 클래스 레벨 @Transactional을 쓰지 않는다: 각 스레드가 서로 다른 커넥션을 써야
// 진짜 동시성이 재현되고, 그러려면 픽스처가 커밋되어 있어야 한다. 대신 라운드마다·종료 시 만든 데이터를 직접 지운다.
@SpringBootTest
class ActivityCancelConcurrentTest {

    private static final int ROUNDS = 20; // 경합은 확률적이라 여러 번 반복해 락이 없을 때 실패할 가능성을 높인다

    @Autowired private ActivityCancelService activityCancelService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final List<Long> createdUserIds = new ArrayList<>();
    private final List<Long> createdPostIds = new ArrayList<>();
    private final List<Long> createdMatchIds = new ArrayList<>();

    private Long createUser(String label) {
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, nickname, email_verified_at) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class,
                "activity-cancel-race-" + UUID.randomUUID() + "@example.com", "dummy-hash", label, OffsetDateTime.now()
        );
        createdUserIds.add(id);
        return id;
    }

    private Long createPost(Long userId, OffsetDateTime start) {
        Long id = jdbcTemplate.queryForObject(
                """
                INSERT INTO match_request (user_id, status, scheduled_at, talk_level, search_expires_at)
                VALUES (?, 'MATCHED', ?, 'LIGHT_CHAT', ?)
                RETURNING id
                """,
                Long.class, userId, start, start.minusHours(1)
        );
        createdPostIds.add(id);
        return id;
    }

    // 3일 뒤 시작하는 확정 매칭과 참가자 2명(신청자 게시글은 없을 수 있다)
    private Long createConfirmedMatch(Long hostId, Long hostPostId, Long applicantId, Long applicantPostId,
                                      OffsetDateTime start) {
        Long matchId = jdbcTemplate.queryForObject(
                """
                INSERT INTO activity_match
                    (scheduled_at, scheduled_end_at, talk_level, location, course_name,
                     distance_min_meters, distance_max_meters, route_description,
                     agreed_pace_min_sec, agreed_pace_max_sec, status,
                     decision_expires_at, meeting_code, confirmed_at, created_at)
                VALUES (?, ?, 'LIGHT_CHAT', '만나는 곳', '뚝섬 한강공원', 5000, 8000, '코스 설명', 360, 400, 'CONFIRMED',
                        ?, '123456', ?, ?)
                RETURNING id
                """,
                Long.class, start, start.plusHours(2), start.minusDays(2), start.minusDays(1), start.minusDays(3)
        );
        createdMatchIds.add(matchId);
        jdbcTemplate.update(
                "INSERT INTO match_participant (activity_match_id, match_request_id, user_id, slot, accept_status) "
                        + "VALUES (?, ?, ?, 'A', 'ACCEPTED')", matchId, hostPostId, hostId);
        jdbcTemplate.update(
                "INSERT INTO match_participant (activity_match_id, match_request_id, user_id, slot, accept_status) "
                        + "VALUES (?, ?, ?, 'B', 'ACCEPTED')", matchId, applicantPostId, applicantId);
        return matchId;
    }

    // 자식 테이블부터 지운다. 테스트가 실패해도 남은 것을 전부 찾아 지운다(없으면 아무 일도 하지 않는다).
    private void deleteMatch(Long matchId) {
        jdbcTemplate.update("DELETE FROM chat_message WHERE activity_match_id = ?", matchId);
        jdbcTemplate.update("DELETE FROM match_participant WHERE activity_match_id = ?", matchId);
        jdbcTemplate.update("DELETE FROM activity_match WHERE id = ?", matchId);
    }

    private void deletePost(Long postId) {
        jdbcTemplate.update("DELETE FROM match_request WHERE id = ?", postId);
    }

    @AfterEach
    void cleanUp() {
        createdMatchIds.forEach(this::deleteMatch);
        createdPostIds.forEach(this::deletePost);
        createdUserIds.forEach(id -> jdbcTemplate.update("DELETE FROM users WHERE id = ?", id));
    }

    @Test
    @DisplayName("호스트와 신청자가 동시에 취소해도 한쪽만 성공하고, 매칭·참가자·게시글·채팅이 항상 일치한다")
    void 동시_취소_경합() throws Exception {
        Long host = createUser("호스트");
        Long applicant = createUser("신청자");

        for (int round = 0; round < ROUNDS; round++) {
            OffsetDateTime start = OffsetDateTime.now().plusDays(3);
            // 신청자 게시글이 있는 라운드와 없는 라운드를 번갈아 돌린다
            boolean applicantHasPost = round % 2 == 0;
            Long hostPost = createPost(host, start);
            Long applicantPost = applicantHasPost ? createPost(applicant, start) : null;
            Long matchId = createConfirmedMatch(host, hostPost, applicant, applicantPost, start);

            AtomicReference<Object> hostResult = new AtomicReference<>();
            AtomicReference<Object> applicantResult = new AtomicReference<>();
            runConcurrently(
                    task(() -> { activityCancelService.cancel(host, matchId); return "취소됨"; }, hostResult),
                    task(() -> { activityCancelService.cancel(applicant, matchId); return "취소됨"; }, applicantResult)
            );

            boolean hostWon = "취소됨".equals(hostResult.get());
            boolean applicantWon = "취소됨".equals(applicantResult.get());
            // 정확히 한쪽만 성공하고, 진 쪽은 "이미 취소·종료됨" 409여야 한다(다른 예외는 안 된다)
            assertThat(hostWon ^ applicantWon)
                    .as("라운드 %d: 호스트=%s, 신청자=%s", round, hostResult.get(), applicantResult.get()).isTrue();
            assertThat(hostWon ? applicantResult.get() : hostResult.get())
                    .isInstanceOf(MatchRequestNotSearchingException.class);

            Long winner = hostWon ? host : applicant;
            assertThat(query("SELECT status FROM activity_match WHERE id = ?", String.class, matchId))
                    .as("라운드 %d", round).isEqualTo("CANCELLED");
            assertThat(query("SELECT closed_by_user_id FROM activity_match WHERE id = ?", Long.class, matchId))
                    .as("라운드 %d", round).isEqualTo(winner);
            assertThat(query("SELECT COUNT(*) FROM match_participant WHERE activity_match_id = ? AND released_at IS NOT NULL",
                    Integer.class, matchId)).as("라운드 %d", round).isEqualTo(2);

            // 취소한 사람의 글은 CANCELLED, 상대의 글은 SEARCHING — 두 취소가 뒤섞이면 이 규칙이 깨진다
            assertThat(query("SELECT status FROM match_request WHERE id = ?", String.class, hostPost))
                    .as("라운드 %d 호스트 글", round).isEqualTo(hostWon ? "CANCELLED" : "SEARCHING");
            if (applicantPost != null) {
                assertThat(query("SELECT status FROM match_request WHERE id = ?", String.class, applicantPost))
                        .as("라운드 %d 신청자 글", round).isEqualTo(hostWon ? "SEARCHING" : "CANCELLED");
            }

            // 취소 메시지는 성공한 한 사람 몫만 한 번 남는다
            List<Map<String, Object>> messages = jdbcTemplate.queryForList(
                    "SELECT sender_id FROM chat_message WHERE activity_match_id = ?", matchId);
            assertThat(messages).as("라운드 %d 채팅 메시지 수", round).hasSize(1);
            assertThat(messages.get(0).get("sender_id")).as("라운드 %d", round).isEqualTo(winner);

            // 다음 라운드를 위해 정리(사용자당 활성 게시글·활성 참가는 하나뿐이라 남겨둘 수 없다)
            deleteMatch(matchId);
            deletePost(hostPost);
            if (applicantPost != null) {
                deletePost(applicantPost);
            }
        }
    }

    private <T> T query(String sql, Class<T> type, Object... args) {
        return jdbcTemplate.queryForObject(sql, type, args);
    }

    private Callable<Void> task(Callable<Object> body, AtomicReference<Object> result) {
        return () -> {
            try {
                result.set(body.call());
            } catch (Exception e) {
                result.set(e);
            }
            return null;
        };
    }

    // 두 작업이 모두 출발선에 선 것을 확인한 뒤 동시에 출발시킨다(MatchingConcurrentApplyTest와 같은 방식)
    private void runConcurrently(Callable<Void> a, Callable<Void> b) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var f1 = executor.submit(() -> { ready.countDown(); start.await(); return a.call(); });
            var f2 = executor.submit(() -> { ready.countDown(); start.await(); return b.call(); });
            assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            f1.get(5, TimeUnit.SECONDS);
            f2.get(5, TimeUnit.SECONDS);
        } finally {
            start.countDown(); // assertion이 먼저 실패해도 대기 중인 스레드를 깨워 종료시킨다
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
