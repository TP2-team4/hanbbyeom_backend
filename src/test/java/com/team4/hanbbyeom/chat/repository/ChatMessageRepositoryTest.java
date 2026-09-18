package com.team4.hanbbyeom.chat.repository;

import com.team4.hanbbyeom.chat.domain.ChatMessage;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 실제 PostgreSQL에서 chat_message의 저장 구조, 제약조건, 조회 순서를 확인
// Service와 Controller는 다음 커밋에서 추가하므로 이번 테스트는 Repository와 DB 동작만 검증
@SpringBootTest
@Transactional
class ChatMessageRepositoryTest {

    @Autowired
    private ChatMessageRepository chatMessageRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    private Long activityMatchId;
    private Long senderId;
    private Long otherActivityMatchId;
    private Long otherSenderId;

    @BeforeEach
    void setUp() {
        Long hostId = createUser("채팅호스트");
        senderId = createUser("채팅신청자");
        activityMatchId = createConfirmedMatch(hostId, senderId, "첫 번째 코스");

        Long otherHostId = createUser("다른호스트");
        otherSenderId = createUser("다른신청자");
        otherActivityMatchId = createConfirmedMatch(otherHostId, otherSenderId, "두 번째 코스");
    }

    @Test
    void 메시지를_저장하고_다시_조회할_수_있다() {
        ChatMessage saved = chatMessageRepository.saveAndFlush(
                new ChatMessage(activityMatchId, senderId, "도착했어요.")
        );

        // 영속성 컨텍스트를 비워 DB에 실제로 저장된 값을 다시 조회
        entityManager.clear();
        ChatMessage found = chatMessageRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getActivityMatchId()).isEqualTo(activityMatchId);
        assertThat(found.getSenderId()).isEqualTo(senderId);
        assertThat(found.getContent()).isEqualTo("도착했어요.");
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    void 매칭별_메시지를_아이디_오름차순으로_조회한다() {
        ChatMessage first = saveMessage(activityMatchId, senderId, "첫 번째 메시지");
        ChatMessage second = saveMessage(activityMatchId, senderId, "두 번째 메시지");
        saveMessage(otherActivityMatchId, otherSenderId, "다른 채팅방 메시지");

        List<ChatMessage> messages =
                chatMessageRepository.findByActivityMatchIdOrderByIdAsc(activityMatchId);

        assertThat(messages)
                .extracting(ChatMessage::getId)
                .containsExactly(first.getId(), second.getId());
    }

    @Test
    void 마지막으로_받은_아이디_이후의_메시지만_조회한다() {
        ChatMessage first = saveMessage(activityMatchId, senderId, "첫 번째 메시지");
        ChatMessage second = saveMessage(activityMatchId, senderId, "두 번째 메시지");
        ChatMessage third = saveMessage(activityMatchId, senderId, "세 번째 메시지");

        List<ChatMessage> messages =
                chatMessageRepository.findByActivityMatchIdAndIdGreaterThanOrderByIdAsc(
                        activityMatchId,
                        first.getId()
                );

        assertThat(messages)
                .extracting(ChatMessage::getId)
                .containsExactly(second.getId(), third.getId());
    }

    @Test
    void 가장_최근에_저장된_메시지를_조회한다() {
        saveMessage(activityMatchId, senderId, "첫 번째 메시지");
        ChatMessage latest = saveMessage(activityMatchId, senderId, "마지막 메시지");

        ChatMessage found = chatMessageRepository
                .findTopByActivityMatchIdOrderByIdDesc(activityMatchId)
                .orElseThrow();

        assertThat(found.getId()).isEqualTo(latest.getId());
        assertThat(found.getContent()).isEqualTo("마지막 메시지");
    }

    @Test
    void 매칭에_참여하지_않은_사용자는_발신자로_저장할_수_없다() {
        assertThatThrownBy(() -> chatMessageRepository.saveAndFlush(
                new ChatMessage(activityMatchId, otherSenderId, "참가자가 아닌 사용자의 메시지")
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 공백으로만_이루어진_메시지는_저장할_수_없다() {
        assertThatThrownBy(() -> chatMessageRepository.saveAndFlush(
                new ChatMessage(activityMatchId, senderId, "   ")
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 메시지가_100자를_초과하면_저장할_수_없다() {
        assertThatThrownBy(() -> chatMessageRepository.saveAndFlush(
                new ChatMessage(activityMatchId, senderId, "가".repeat(101))
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    private ChatMessage saveMessage(Long matchId, Long userId, String content) {
        return chatMessageRepository.saveAndFlush(new ChatMessage(matchId, userId, content));
    }

    // users의 계정 생명주기 제약을 만족하도록 탈퇴하지 않은 테스트 사용자를 생성
    private Long createUser(String nickname) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO users (email, password_hash, nickname, email_verified_at)
                VALUES (?, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                "chat-" + System.nanoTime() + "@example.com",
                "dummy-hash",
                nickname,
                OffsetDateTime.now()
        );
    }

    // 채팅 저장 제약을 검증하기 위해 확정된 매칭과 호스트·신청자 참가 기록을 함께 생성
    private Long createConfirmedMatch(Long hostId, Long applicantId, String courseName) {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime decisionExpiresAt = now.plusHours(1);
        OffsetDateTime scheduledAt = now.plusHours(2);
        OffsetDateTime scheduledEndAt = now.plusHours(3);

        Long hostRequestId = jdbcTemplate.queryForObject(
                """
                INSERT INTO match_request (
                    user_id, scheduled_at, talk_level, status, search_expires_at
                )
                VALUES (?, ?, 'SILENT', 'MATCHED', ?)
                RETURNING id
                """,
                Long.class,
                hostId,
                scheduledAt,
                decisionExpiresAt
        );

        Long matchId = jdbcTemplate.queryForObject(
                """
                INSERT INTO activity_match (
                    scheduled_at, scheduled_end_at, talk_level, location, course_name,
                    distance_min_meters, distance_max_meters, route_description,
                    agreed_pace_min_sec, agreed_pace_max_sec, status,
                    decision_expires_at, meeting_code, confirmed_at
                )
                VALUES (?, ?, 'SILENT', '테스트 장소', ?, 5000, 8000, '테스트 경로',
                        360, 400, 'CONFIRMED', ?, '123456', ?)
                RETURNING id
                """,
                Long.class,
                scheduledAt,
                scheduledEndAt,
                courseName,
                decisionExpiresAt,
                now
        );

        jdbcTemplate.update(
                """
                INSERT INTO match_participant (
                    activity_match_id, match_request_id, user_id, slot,
                    accept_status, responded_at
                )
                VALUES (?, ?, ?, 'A', 'ACCEPTED', ?)
                """,
                matchId,
                hostRequestId,
                hostId,
                now
        );

        // 신청자는 자신의 모집글 없이도 참여할 수 있으므로 match_request_id를 NULL로 저장
        jdbcTemplate.update(
                """
                INSERT INTO match_participant (
                    activity_match_id, match_request_id, user_id, slot,
                    accept_status, responded_at
                )
                VALUES (?, NULL, ?, 'B', 'ACCEPTED', ?)
                """,
                matchId,
                applicantId,
                now
        );

        return matchId;
    }
}
