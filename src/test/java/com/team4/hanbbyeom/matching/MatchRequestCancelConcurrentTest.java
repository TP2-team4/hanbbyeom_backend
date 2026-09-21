package com.team4.hanbbyeom.matching;

import com.team4.hanbbyeom.matching.dto.MatchRequestCreateRequest;
import com.team4.hanbbyeom.matching.exception.MatchRequestNotSearchingException;
import com.team4.hanbbyeom.matching.service.MatchApplyService;
import com.team4.hanbbyeom.matching.service.MatchRequestCommandService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

// 같은 모집글에 신청과 취소가 "동시에" 들어왔을 때 matching_mutex 락 덕분에 한쪽만 성공하고 게시글과 매칭이 어긋나지 않는지
// 검증한다(이슈 #100). 상태 검사만 있고 락이 없으면 apply()가 게시글을 PENDING_CONFIRMATION으로 바꾸는 것과 동시에 들어온
// 취소가 둘 다 SEARCHING으로 읽고 통과해 "게시글 CANCELLED + 매칭 PROPOSED" 불일치가 남는다.
//
// MatchingConcurrentApplyTest와 같은 이유로 클래스 레벨 @Transactional을 쓰지 않는다: 각 스레드가 서로 다른 커넥션을 써야
// 진짜 동시성이 재현되고, 그러려면 픽스처가 커밋되어 있어야 한다. 대신 라운드마다·종료 시 만든 데이터를 직접 지운다.
@SpringBootTest
class MatchRequestCancelConcurrentTest {

    private static final int ROUNDS = 20; // 경합은 확률적이라 여러 번 반복해 락이 없을 때 실패할 가능성을 높인다

    @Autowired private MatchRequestCommandService matchRequestCommandService;
    @Autowired private MatchApplyService matchApplyService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final List<Long> createdUserIds = new ArrayList<>();
    private final List<Long> createdRequestIds = new ArrayList<>();

    private Long createUser(String label) {
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, nickname, email_verified_at) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class,
                "cancel-race-" + UUID.randomUUID() + "@example.com", "dummy-hash", label, OffsetDateTime.now()
        );
        createdUserIds.add(id);
        return id;
    }

    // 게시글에 딸린 매칭·참가자·러닝 조건·게시글을 자식 테이블부터 지운다. 테스트가 실패해도 남은 것을 전부 찾아 지운다.
    private void deletePostAndMatches(Long requestId) {
        List<Long> matchIds = jdbcTemplate.queryForList(
                "SELECT DISTINCT activity_match_id FROM match_participant WHERE match_request_id = ?", Long.class, requestId);
        for (Long matchId : matchIds) {
            jdbcTemplate.update("DELETE FROM match_participant WHERE activity_match_id = ?", matchId);
            jdbcTemplate.update("DELETE FROM activity_match WHERE id = ?", matchId);
        }
        jdbcTemplate.update("DELETE FROM run_match_condition WHERE match_request_id = ?", requestId);
        jdbcTemplate.update("DELETE FROM match_request WHERE id = ?", requestId);
    }

    @AfterEach
    void cleanUp() {
        for (Long requestId : createdRequestIds) {
            deletePostAndMatches(requestId);
        }
        for (Long userId : createdUserIds) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
    }

    @Test
    @DisplayName("신청과 취소가 동시에 들어와도 한쪽만 성공하고, 게시글 CANCELLED와 매칭 없음이 항상 일치한다")
    void 신청과_취소_경합() throws Exception {
        Long hostId = createUser("호스트");
        Long applicantId = createUser("신청자");
        Long courseId = jdbcTemplate.queryForObject(
                "SELECT id FROM running_course WHERE name = ? LIMIT 1", Long.class, "뚝섬 한강공원");

        for (int round = 0; round < ROUNDS; round++) {
            Long requestId = matchRequestCommandService.create(hostId, new MatchRequestCreateRequest(
                    courseId, "취소 경합 테스트 장소", 5000, 8000, 360, 400,
                    OffsetDateTime.now().plusHours(48), "LIGHT_CHAT"));
            createdRequestIds.add(requestId);

            AtomicReference<Object> applyResult = new AtomicReference<>();
            AtomicReference<Object> cancelResult = new AtomicReference<>();
            runConcurrently(
                    task(() -> matchApplyService.apply(applicantId, requestId), applyResult),
                    task(() -> { matchRequestCommandService.cancel(hostId, requestId); return "취소됨"; }, cancelResult)
            );

            boolean applied = applyResult.get() instanceof Long;
            boolean cancelled = "취소됨".equals(cancelResult.get());
            // 정확히 한쪽만 성공하고, 진 쪽은 "모집 중이 아님" 409여야 한다(다른 예외는 안 된다)
            assertThat(applied ^ cancelled)
                    .as("라운드 %d: 신청=%s, 취소=%s", round, applyResult.get(), cancelResult.get()).isTrue();
            Object loser = applied ? cancelResult.get() : applyResult.get();
            assertThat(loser).isInstanceOf(MatchRequestNotSearchingException.class);

            // 게시글이 CANCELLED이면 매칭이 없고, 신청이 성공했으면 게시글이 PENDING_CONFIRMATION이며 매칭이 있다
            String postStatus = jdbcTemplate.queryForObject(
                    "SELECT status FROM match_request WHERE id = ?", String.class, requestId);
            Integer matchCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(DISTINCT activity_match_id) FROM match_participant WHERE match_request_id = ?",
                    Integer.class, requestId);
            if (cancelled) {
                assertThat(postStatus).as("라운드 %d", round).isEqualTo("CANCELLED");
                assertThat(matchCount).as("라운드 %d", round).isZero();
            } else {
                assertThat(postStatus).as("라운드 %d", round).isEqualTo("PENDING_CONFIRMATION");
                assertThat(matchCount).as("라운드 %d", round).isEqualTo(1);
            }

            // 다음 라운드를 위해 정리(사용자당 활성 게시글·활성 참가는 하나뿐이라 남겨둘 수 없다)
            deletePostAndMatches(requestId);
        }
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
