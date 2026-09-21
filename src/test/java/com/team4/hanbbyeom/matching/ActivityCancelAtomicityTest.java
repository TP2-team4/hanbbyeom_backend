package com.team4.hanbbyeom.matching;

import com.team4.hanbbyeom.chat.domain.ChatMessage;
import com.team4.hanbbyeom.chat.repository.ChatMessageRepository;
import com.team4.hanbbyeom.matching.event.ActivityCancelledEvent;
import com.team4.hanbbyeom.matching.service.ActivityCancelService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// 활동 취소와 채팅 취소 메시지가 "함께 성공하거나 함께 실패"하는지 검증한다(이슈 #109). 취소만 커밋되고 메시지가 없으면 상대는
// 채팅 폴링으로 취소를 알 수 없고, 메시지만 남고 취소가 롤백되면 상대는 없던 취소를 통보받는다.
// 롤백은 커밋되는 트랜잭션이어야 확인할 수 있어 클래스 레벨 @Transactional을 쓰지 않고, 만든 데이터를 직접 지운다.
@SpringBootTest
class ActivityCancelAtomicityTest {

    @MockitoBean private ChatMessageRepository chatMessageRepository;

    @Autowired private ActivityCancelService activityCancelService;
    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long hostId;
    private Long applicantId;
    private Long hostPostId;
    private Long applicantPostId;
    private Long matchId;

    private Long createUser(String label) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, nickname, email_verified_at) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class,
                "activity-cancel-atomic-" + UUID.randomUUID() + "@example.com", "dummy-hash", label, OffsetDateTime.now()
        );
    }

    private Long createPost(Long userId, OffsetDateTime start) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO match_request (user_id, status, scheduled_at, talk_level, search_expires_at)
                VALUES (?, 'MATCHED', ?, 'LIGHT_CHAT', ?)
                RETURNING id
                """,
                Long.class, userId, start, start.minusHours(1)
        );
    }

    private void createConfirmedMatchWithPosts() {
        OffsetDateTime start = OffsetDateTime.now().plusDays(3);
        hostId = createUser("호스트");
        applicantId = createUser("신청자");
        hostPostId = createPost(hostId, start);
        applicantPostId = createPost(applicantId, start);
        matchId = jdbcTemplate.queryForObject(
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
        jdbcTemplate.update(
                "INSERT INTO match_participant (activity_match_id, match_request_id, user_id, slot, accept_status) "
                        + "VALUES (?, ?, ?, 'A', 'ACCEPTED')", matchId, hostPostId, hostId);
        jdbcTemplate.update(
                "INSERT INTO match_participant (activity_match_id, match_request_id, user_id, slot, accept_status) "
                        + "VALUES (?, ?, ?, 'B', 'ACCEPTED')", matchId, applicantPostId, applicantId);
    }

    @AfterEach
    void cleanUp() {
        if (matchId != null) {
            jdbcTemplate.update("DELETE FROM match_participant WHERE activity_match_id = ?", matchId);
            jdbcTemplate.update("DELETE FROM activity_match WHERE id = ?", matchId);
        }
        for (Long postId : new Long[]{hostPostId, applicantPostId}) {
            if (postId != null) {
                jdbcTemplate.update("DELETE FROM match_request WHERE id = ?", postId);
            }
        }
        for (Long userId : new Long[]{hostId, applicantId}) {
            if (userId != null) {
                jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
            }
        }
    }

    @Test
    @DisplayName("채팅 메시지 저장이 실패하면 취소도 함께 롤백되어 매칭·참가자·게시글이 그대로다")
    void 채팅_저장이_실패하면_취소도_롤백된다() {
        createConfirmedMatchWithPosts();
        when(chatMessageRepository.save(any(ChatMessage.class))).thenThrow(new IllegalStateException("채팅 저장 실패"));

        assertThatThrownBy(() -> activityCancelService.cancel(hostId, matchId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("채팅 저장 실패");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM activity_match WHERE id = ?", String.class, matchId)).isEqualTo("CONFIRMED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT closed_by_user_id FROM activity_match WHERE id = ?", Long.class, matchId)).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM match_participant WHERE activity_match_id = ? AND released_at IS NOT NULL",
                Integer.class, matchId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM match_request WHERE id = ?", String.class, hostPostId)).isEqualTo("MATCHED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM match_request WHERE id = ?", String.class, applicantPostId)).isEqualTo("MATCHED");
    }

    // 리스너는 취소와 같은 트랜잭션에 참여하는 것을 전제로 한다(MANDATORY). 트랜잭션 없이 이벤트가 발행되면 메시지가
    // 별도로 커밋되어 취소와 따로 놀 수 있으므로, 조용히 저장하지 않고 바로 실패해야 한다.
    @Test
    @DisplayName("트랜잭션 밖에서 취소 이벤트가 발행되면 메시지를 저장하지 않고 실패한다")
    void 트랜잭션_밖에서_발행하면_실패한다() {
        assertThatThrownBy(() -> eventPublisher.publishEvent(new ActivityCancelledEvent(1L, 2L)))
                .isInstanceOf(IllegalTransactionStateException.class);

        verifyNoInteractions(chatMessageRepository);
    }
}
