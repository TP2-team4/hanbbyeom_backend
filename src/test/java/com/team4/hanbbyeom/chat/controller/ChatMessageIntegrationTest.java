package com.team4.hanbbyeom.chat.controller;

import com.team4.hanbbyeom.chat.domain.ChatMessage;
import com.team4.hanbbyeom.chat.repository.ChatMessageRepository;
import com.team4.hanbbyeom.global.security.jwt.JwtTokenProvider;
import com.team4.hanbbyeom.matching.domain.ActivityMatchStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 실제 Security Filter와 HTTP 요청을 거쳐 채팅 권한·상태·요청 검증까지 함께 확인
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ChatMessageIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private ChatMessageRepository chatMessageRepository;

    @Test
    void 참가자가_메시지를_전송하면_JWT_사용자가_발신자로_저장된다() throws Exception {
        TestMatch match = createMatch(ActivityMatchStatus.CONFIRMED, false);

        mockMvc.perform(post("/api/matching/matches/{activityMatchId}/messages", match.activityMatchId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(match.applicantId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "2번 출구 앞에 도착했어요."}
                                """))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.senderId").value(match.applicantId()))
                .andExpect(jsonPath("$.content").value("2번 출구 앞에 도착했어요."))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());

        Long storedSenderId = jdbcTemplate.queryForObject(
                "SELECT sender_id FROM chat_message WHERE activity_match_id = ?",
                Long.class,
                match.activityMatchId()
        );
        assertThat(storedSenderId).isEqualTo(match.applicantId());
    }

    @Test
    void 전체_메시지와_마지막_수신_아이디_이후_메시지를_순서대로_조회한다() throws Exception {
        TestMatch match = createMatch(ActivityMatchStatus.CONFIRMED, false);
        ChatMessage first = saveMessage(match.activityMatchId(), match.hostId(), "첫 번째 메시지");
        ChatMessage second = saveMessage(match.activityMatchId(), match.applicantId(), "두 번째 메시지");
        ChatMessage third = saveMessage(match.activityMatchId(), match.hostId(), "세 번째 메시지");

        mockMvc.perform(get("/api/matching/matches/{activityMatchId}/messages", match.activityMatchId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(match.hostId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].id").value(first.getId()))
                .andExpect(jsonPath("$[1].id").value(second.getId()))
                .andExpect(jsonPath("$[2].id").value(third.getId()));

        mockMvc.perform(get("/api/matching/matches/{activityMatchId}/messages", match.activityMatchId())
                        .param("afterId", String.valueOf(first.getId()))
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(match.hostId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(second.getId()))
                .andExpect(jsonPath("$[1].id").value(third.getId()));
    }

    @Test
    void 종료된_매칭의_과거_참가자는_기존_메시지를_조회할_수_있다() throws Exception {
        TestMatch match = createMatch(ActivityMatchStatus.ENDED, true);
        ChatMessage message = saveMessage(match.activityMatchId(), match.hostId(), "활동 전 메시지");

        mockMvc.perform(get("/api/matching/matches/{activityMatchId}/messages", match.activityMatchId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(match.applicantId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(message.getId()));
    }

    @Test
    void 제삼자의_메시지_조회는_403으로_거부한다() throws Exception {
        TestMatch match = createMatch(ActivityMatchStatus.CONFIRMED, false);

        mockMvc.perform(get("/api/matching/matches/{activityMatchId}/messages", match.activityMatchId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(match.outsiderId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("해당 매칭 참가자만 채팅을 이용할 수 있어요."));
    }

    @Test
    void 존재하지_않는_매칭의_메시지_조회는_404로_처리한다() throws Exception {
        Long userId = createUser("없는매칭조회자");

        mockMvc.perform(get("/api/matching/matches/{activityMatchId}/messages", 999_999_999L)
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(userId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("존재하지 않는 매칭이에요."));
    }

    @Test
    void 확정되지_않은_매칭의_채팅_조회는_409로_거부한다() throws Exception {
        TestMatch match = createMatch(ActivityMatchStatus.PROPOSED, false);

        mockMvc.perform(get("/api/matching/matches/{activityMatchId}/messages", match.activityMatchId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(match.hostId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("확정된 매칭에서만 채팅을 이용할 수 있어요."));
    }

    @Test
    void 종료된_매칭에는_새_메시지를_보낼_수_없다() throws Exception {
        TestMatch match = createMatch(ActivityMatchStatus.ENDED, true);

        assertMessageSendConflict(match);
    }

    @Test
    void 종료_스케줄러가_아직_실행되지_않아도_예정_종료_시각이_지나면_전송을_거부한다() throws Exception {
        TestMatch match = createMatch(ActivityMatchStatus.CONFIRMED, true);

        assertMessageSendConflict(match);
    }

    @Test
    void 공백뿐인_메시지와_100자_초과_메시지는_400으로_거부한다() throws Exception {
        TestMatch match = createMatch(ActivityMatchStatus.CONFIRMED, false);
        String token = bearerToken(match.hostId());

        mockMvc.perform(post("/api/matching/matches/{activityMatchId}/messages", match.activityMatchId())
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "   "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("메시지를 입력해주세요."));

        mockMvc.perform(post("/api/matching/matches/{activityMatchId}/messages", match.activityMatchId())
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "%s"}
                                """.formatted("가".repeat(101))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("메시지는 100자 이하여야 합니다."));
    }

    @Test
    void 음수_afterId는_400으로_거부한다() throws Exception {
        TestMatch match = createMatch(ActivityMatchStatus.CONFIRMED, false);

        mockMvc.perform(get("/api/matching/matches/{activityMatchId}/messages", match.activityMatchId())
                        .param("afterId", "-1")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(match.hostId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("afterId는 0 이상이어야 합니다."));
    }

    @Test
    void 숫자가_아닌_afterId는_공통_형식의_400으로_거부한다() throws Exception {
        TestMatch match = createMatch(ActivityMatchStatus.CONFIRMED, false);

        mockMvc.perform(get("/api/matching/matches/{activityMatchId}/messages", match.activityMatchId())
                        .param("afterId", "not-a-number")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(match.hostId())))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value("요청 형식이 올바르지 않습니다."));
    }

    @Test
    void 토큰_없는_메시지_조회는_401로_거부한다() throws Exception {
        TestMatch match = createMatch(ActivityMatchStatus.CONFIRMED, false);

        mockMvc.perform(get("/api/matching/matches/{activityMatchId}/messages", match.activityMatchId()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
    }

    private void assertMessageSendConflict(TestMatch match) throws Exception {
        mockMvc.perform(post("/api/matching/matches/{activityMatchId}/messages", match.activityMatchId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(match.hostId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "종료 후 메시지"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("종료된 매칭에는 새 메시지를 보낼 수 없어요."));
    }

    private ChatMessage saveMessage(Long activityMatchId, Long senderId, String content) {
        return chatMessageRepository.saveAndFlush(new ChatMessage(activityMatchId, senderId, content));
    }

    private String bearerToken(Long userId) {
        return "Bearer " + jwtTokenProvider.createAccessToken(userId);
    }

    private Long createUser(String nickname) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO users (email, password_hash, nickname, email_verified_at)
                VALUES (?, 'dummy-hash', ?, ?)
                RETURNING id
                """,
                Long.class,
                "chat-api-" + System.nanoTime() + "@example.com",
                nickname,
                OffsetDateTime.now()
        );
    }

    // 실제 매칭 생성 흐름을 호출하지 않고 테스트 목적에 맞는 상태와 시간의 매칭을 직접 구성
    // 과거 종료 매칭은 참가자의 released_at도 채워서 종료 후 조회 권한이 유지되는지 함께 확인
    private TestMatch createMatch(ActivityMatchStatus status, boolean scheduledEndPassed) {
        Long hostId = createUser("채팅호스트");
        Long applicantId = createUser("채팅신청자");
        Long outsiderId = createUser("채팅제삼자");

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime createdAt;
        OffsetDateTime decisionExpiresAt;
        OffsetDateTime scheduledAt;
        OffsetDateTime scheduledEndAt;

        if (scheduledEndPassed) {
            createdAt = now.minusHours(4);
            decisionExpiresAt = now.minusHours(3);
            scheduledAt = now.minusHours(2);
            scheduledEndAt = now.minusHours(1);
        } else {
            createdAt = now.minusHours(2);
            decisionExpiresAt = status == ActivityMatchStatus.PROPOSED
                    ? now.plusMinutes(30)
                    : now.minusHours(1);
            scheduledAt = now.plusHours(1);
            scheduledEndAt = now.plusHours(2);
        }

        boolean confirmedBefore = status == ActivityMatchStatus.CONFIRMED
                || status == ActivityMatchStatus.ENDED;
        OffsetDateTime confirmedAt = confirmedBefore ? createdAt.plusMinutes(30) : null;
        OffsetDateTime closedAt = status == ActivityMatchStatus.ENDED ? scheduledEndAt : null;
        String meetingCode = confirmedBefore ? "123456" : null;

        Long activityMatchId = jdbcTemplate.queryForObject(
                """
                INSERT INTO activity_match (
                    scheduled_at, scheduled_end_at, talk_level, location, course_name,
                    distance_min_meters, distance_max_meters, route_description,
                    agreed_pace_min_sec, agreed_pace_max_sec, status,
                    decision_expires_at, meeting_code, confirmed_at, closed_at, created_at
                )
                VALUES (?, ?, 'SILENT', '테스트 장소', '채팅 테스트 코스',
                        5000, 8000, '테스트 경로', 360, 400, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                scheduledAt,
                scheduledEndAt,
                status.name(),
                decisionExpiresAt,
                meetingCode,
                confirmedAt,
                closedAt,
                createdAt
        );

        String hostAcceptStatus = status == ActivityMatchStatus.PROPOSED ? "PENDING" : "ACCEPTED";
        OffsetDateTime releasedAt = status == ActivityMatchStatus.ENDED ? closedAt : null;

        createParticipant(activityMatchId, hostId, "A", hostAcceptStatus, releasedAt);
        createParticipant(activityMatchId, applicantId, "B", "ACCEPTED", releasedAt);

        return new TestMatch(activityMatchId, hostId, applicantId, outsiderId);
    }

    private void createParticipant(
            Long activityMatchId,
            Long userId,
            String slot,
            String acceptStatus,
            OffsetDateTime releasedAt
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO match_participant (
                    activity_match_id, match_request_id, user_id, slot,
                    accept_status, responded_at, released_at
                )
                VALUES (?, NULL, ?, ?, ?, ?, ?)
                """,
                activityMatchId,
                userId,
                slot,
                acceptStatus,
                "PENDING".equals(acceptStatus) ? null : OffsetDateTime.now(),
                releasedAt
        );
    }

    private record TestMatch(
            Long activityMatchId,
            Long hostId,
            Long applicantId,
            Long outsiderId
    ) {
    }
}
