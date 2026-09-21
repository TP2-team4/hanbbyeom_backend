package com.team4.hanbbyeom.matching;

import com.team4.hanbbyeom.matching.domain.MatchRequestStatus;
import com.team4.hanbbyeom.matching.domain.TalkLevel;
import com.team4.hanbbyeom.matching.dto.MatchRequestCreateRequest;
import com.team4.hanbbyeom.matching.exception.MatchRequestNotSearchingException;
import com.team4.hanbbyeom.matching.repository.MatchRequestRepository;
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

// 같은 모집글에 두 사용자가 "동시에" 신청했을 때, matching_mutex 행 잠금(SELECT ... FOR UPDATE)
// 덕분에 오직 한쪽만 성공하는지 검증한다.
//
// 이 클래스만 클래스 레벨 @Transactional을 의도적으로 안 쓴다: 테스트 롤백 방식은 테스트
// 메서드 전체를 하나의 트랜잭션(하나의 커넥션)으로 묶는데, 진짜 동시성을 재현하려면 각
// 스레드가 서로 다른 커넥션/트랜잭션을 써야 한다. 같은 트랜잭션 안에 있으면 아직 커밋되지
// 않은 픽스처 데이터를 다른 스레드(다른 커넥션)가 아예 못 보게 되어 테스트가 성립하지 않는다.
// 대신 @AfterEach에서 생성한 데이터를 직접 지운다.
@SpringBootTest
class MatchingConcurrentApplyTest {

    @Autowired private MatchRequestCommandService matchRequestCommandService;
    @Autowired private MatchApplyService matchApplyService;
    @Autowired private MatchRequestRepository matchRequestRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final List<Long> createdUserIds = new ArrayList<>();
    private Long hostRequestId;

    private Long createUser(String label) {
        Long id = jdbcTemplate.queryForObject(
                """
                INSERT INTO users (email, password_hash, nickname, email_verified_at)
                VALUES (?, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                "concurrent-" + UUID.randomUUID() + "@example.com", "dummy-hash", label, OffsetDateTime.now()
        );
        createdUserIds.add(id);
        return id;
    }

    @AfterEach
    void cleanUp() {
        // 여긴 @Transactional 롤백이 없으므로, 커밋된 테스트 데이터를 자식 테이블부터 직접 지운다.
        //
        // activity_match id를 테스트 성공/실패 결과(assertion)에 의존해서 기록해두지 않고,
        // 매번 DB에서 hostRequestId로 다시 조회한다 — 동시성 버그로 두 신청이 모두 성공해서
        // activity_match가 2건 생겼거나, assertion이 먼저 실패해서 그 기록 코드 자체가 실행 안
        // 됐어도, 여기선 항상 실제로 남아있는 걸 전부 찾아서 지울 수 있다(리뷰로 발견됨 —
        // 이 테스트가 정작 자신이 잡으려는 회귀가 터졌을 때 스스로 정리를 못 하는 문제였음).
        if (hostRequestId != null) {
            List<Long> activityMatchIds = jdbcTemplate.queryForList(
                    "SELECT DISTINCT activity_match_id FROM match_participant WHERE match_request_id = ?",
                    Long.class, hostRequestId
            );
            for (Long activityMatchId : activityMatchIds) {
                jdbcTemplate.update("DELETE FROM match_participant WHERE activity_match_id = ?", activityMatchId);
                jdbcTemplate.update("DELETE FROM activity_match WHERE id = ?", activityMatchId);
            }
            jdbcTemplate.update("DELETE FROM run_match_condition WHERE match_request_id = ?", hostRequestId);
            jdbcTemplate.update("DELETE FROM match_request WHERE id = ?", hostRequestId);
        }
        for (Long userId : createdUserIds) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
    }

    @Test
    @DisplayName("같은 모집글에 두 명이 동시에 신청하면 한 명만 성공하고 한 명은 거부된다")
    void 동시_신청_경합() throws Exception {
        Long hostUserId = createUser("호스트");
        Long applicant1Id = createUser("신청자1");
        Long applicant2Id = createUser("신청자2");

        Long courseId = jdbcTemplate.queryForObject(
                "SELECT id FROM running_course WHERE name = ? LIMIT 1", Long.class, "뚝섬 한강공원");

        // 여기서 create()는 그 자체로 @Transactional이라 즉시 커밋된다 — 이후 별도 스레드에서도 바로 보임
        hostRequestId = matchRequestCommandService.create(hostUserId, new MatchRequestCreateRequest(
                courseId, "동시경합 테스트 장소", 5000, 8000, 360, 400,
                OffsetDateTime.now().plusHours(48), TalkLevel.LIGHT_CHAT
        ));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Object> result1 = new AtomicReference<>();
        AtomicReference<Object> result2 = new AtomicReference<>();

        Callable<Void> attempt1 = applyTask(applicant1Id, ready, start, result1);
        Callable<Void> attempt2 = applyTask(applicant2Id, ready, start, result2);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var future1 = executor.submit(attempt1);
            var future2 = executor.submit(attempt2);

            // 두 스레드 모두 신청 직전(start 대기 상태)까지 진입한 걸 확인한 뒤 동시에 출발시킨다.
            // 이 assertThat이 실패하면(스레드가 제때 ready 못 함) 아래 start.countDown()이
            // 실행되기 전에 예외가 던져져서, 이미 start.await()에 걸려 있는 작업 스레드들이
            // 영원히 못 깨어날 수 있다 — finally에서 무조건 latch를 풀고 강제 종료해야 한다
            // (리뷰로 발견됨).
            assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            future1.get(5, TimeUnit.SECONDS);
            future2.get(5, TimeUnit.SECONDS);
        } finally {
            // 위에서 무슨 이유로 일찍 빠져나가든(assertion 실패 포함), 대기 중인 스레드를 먼저
            // 깨우고(start.countDown()은 여러 번 불러도 안전 — CountDownLatch는 0 밑으로 안 내려감)
            // shutdownNow()로 인터럽트를 걸어 강제 종료한 뒤, 실제로 끝날 때까지 기다린다.
            // shutdown()만으로는 이미 실행 중인(=start.await()에 걸린) 작업을 못 끊어서
            // 테스트 JVM이 안 끝날 수 있었다(리뷰로 발견됨).
            start.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }

        List<Object> results = List.of(result1.get(), result2.get());
        long successCount = results.stream().filter(r -> r instanceof Long).count();
        long conflictCount = results.stream().filter(r -> r instanceof MatchRequestNotSearchingException).count();

        // 정확히 한쪽만 성공(activityMatchId 반환)하고, 다른 한쪽은 "이미 마감된 모집글" 예외로 거부돼야 한다
        assertThat(successCount).isEqualTo(1);
        assertThat(conflictCount).isEqualTo(1);

        assertThat(matchRequestRepository.findById(hostRequestId).orElseThrow().getStatus())
                .isEqualTo(MatchRequestStatus.PENDING_CONFIRMATION);
    }

    // 두 스레드 모두 ready.countDown() 이후 start를 기다렸다가 동시에 apply()를 호출하도록 감싼다.
    // 성공하면 결과(Long activityMatchId)를, 실패하면 예외 객체를 그대로 AtomicReference에 담는다.
    private Callable<Void> applyTask(Long applicantUserId, CountDownLatch ready, CountDownLatch start,
                                     AtomicReference<Object> resultHolder) {
        return () -> {
            ready.countDown();
            start.await();
            try {
                resultHolder.set(matchApplyService.apply(applicantUserId, hostRequestId));
            } catch (Exception e) {
                resultHolder.set(e);
            }
            return null;
        };
    }
}
