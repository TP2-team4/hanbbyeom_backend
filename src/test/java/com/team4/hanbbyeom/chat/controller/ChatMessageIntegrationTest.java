package com.team4.hanbbyeom.chat.controller;

import com.team4.hanbbyeom.chat.domain.ChatMessage;
import com.team4.hanbbyeom.chat.repository.ChatMessageRepository;
import com.team4.hanbbyeom.global.security.jwt.JwtTokenProvider;
import com.team4.hanbbyeom.matching.domain.ActivityMatchStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
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

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private ChatMessageRepository chatMessageRepository;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void setUpFixedClock() {
        setCurrentTime(OffsetDateTime.now(SERVICE_ZONE));
    }

    @Test
    @DisplayName("메시지 전송 시 요청 본문이 아닌 JWT 사용자를 발신자로 저장")
    void 메시지_전송_발신자_저장() throws Exception {
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
    @DisplayName("전체 메시지와 afterId 이후 메시지를 id 오름차순으로 조회")
    void 전체_및_afterId_이후_메시지_조회() throws Exception {
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
    @DisplayName("종료된 매칭의 과거 참가자도 기존 메시지 조회 가능")
    void 종료_매칭_기존_메시지_조회() throws Exception {
        TestMatch match = createMatch(ActivityMatchStatus.ENDED, true);
        ChatMessage message = saveMessage(match.activityMatchId(), match.hostId(), "활동 전 메시지");

        mockMvc.perform(get("/api/matching/matches/{activityMatchId}/messages", match.activityMatchId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(match.applicantId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(message.getId()));
    }

    @Test
    @DisplayName("매칭 참가자가 아닌 제삼자의 메시지 조회는 403")
    void 제삼자_메시지_조회_거부() throws Exception {
        TestMatch match = createMatch(ActivityMatchStatus.CONFIRMED, false);

        mockMvc.perform(get("/api/matching/matches/{activityMatchId}/messages", match.activityMatchId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(match.outsiderId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("해당 매칭 참가자만 채팅을 이용할 수 있어요."));
    }

    @Test
    @DisplayName("존재하지 않는 매칭의 메시지 조회는 404")
    void 없는_매칭_메시지_조회_거부() throws Exception {
        Long userId = createUser("없는매칭조회자");

        mockMvc.perform(get("/api/matching/matches/{activityMatchId}/messages", 999_999_999L)
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(userId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("존재하지 않는 매칭이에요."));
    }

    @Test
    @DisplayName("확정되지 않은 매칭의 채팅 조회는 409")
    void 미확정_매칭_채팅_조회_거부() throws Exception {
        TestMatch match = createMatch(ActivityMatchStatus.PROPOSED, false);

        mockMvc.perform(get("/api/matching/matches/{activityMatchId}/messages", match.activityMatchId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(match.hostId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("확정된 매칭에서만 채팅을 이용할 수 있어요."));
    }

    @Test
    @DisplayName("ENDED 상태여도 활동 예정일 자정 직전이면 메시지 전송 성공")
    void 종료_상태_활동_당일_전송() throws Exception {
        OffsetDateTime fixedNow = OffsetDateTime.parse("2026-09-18T23:59:59.999999999+09:00");
        setCurrentTime(fixedNow);
        TestMatch match = createMatch(
                ActivityMatchStatus.ENDED,
                OffsetDateTime.parse("2026-09-18T19:00:00+09:00"),
                OffsetDateTime.parse("2026-09-18T21:00:00+09:00")
        );

        mockMvc.perform(post("/api/matching/matches/{activityMatchId}/messages", match.activityMatchId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(match.hostId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "변경된 시간에 맞춰 도착할게요."}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.senderId").value(match.hostId()))
                .andExpect(jsonPath("$.content").value("변경된 시간에 맞춰 도착할게요."));
    }

    @Test
    @DisplayName("일반 활동은 예정일 다음 날 자정부터 메시지 전송 차단")
    void 활동_예정일_경과_전송_거부() throws Exception {
        setCurrentTime(OffsetDateTime.parse("2026-09-19T00:00:00+09:00"));
        TestMatch match = createMatch(
                ActivityMatchStatus.ENDED,
                OffsetDateTime.parse("2026-09-18T19:00:00+09:00"),
                OffsetDateTime.parse("2026-09-18T21:00:00+09:00")
        );

        assertMessageSendTimeConflict(match);
    }

    @Test
    @DisplayName("자정을 넘겨 끝나는 심야 활동은 예정 종료 시각까지 전송 성공")
    void 심야_활동_종료_시각까지_전송() throws Exception {
        setCurrentTime(OffsetDateTime.parse("2026-09-19T00:30:00+09:00"));
        TestMatch match = createMatch(
                ActivityMatchStatus.CONFIRMED,
                OffsetDateTime.parse("2026-09-18T23:00:00+09:00"),
                OffsetDateTime.parse("2026-09-19T01:00:00+09:00")
        );

        assertMessageSendCreated(match, "심야 활동 중 메시지");
    }

    @Test
    @DisplayName("심야 활동도 전송 마감 시각부터는 메시지 전송 차단")
    void 전송_마감_경과_전송_거부() throws Exception {
        setCurrentTime(OffsetDateTime.parse("2026-09-19T01:00:00+09:00"));
        TestMatch match = createMatch(
                ActivityMatchStatus.CONFIRMED,
                OffsetDateTime.parse("2026-09-18T23:00:00+09:00"),
                OffsetDateTime.parse("2026-09-19T01:00:00+09:00")
        );

        assertMessageSendTimeConflict(match);
    }

    @Test
    @DisplayName("확정 후 취소 상태는 전송 마감 전이어도 메시지 전송 차단")
    void 취소_상태_전송_거부() throws Exception {
        setCurrentTime(OffsetDateTime.parse("2026-09-18T20:00:00+09:00"));
        TestMatch match = createMatch(
                ActivityMatchStatus.CANCELLED,
                OffsetDateTime.parse("2026-09-18T19:00:00+09:00"),
                OffsetDateTime.parse("2026-09-18T21:00:00+09:00")
        );

        assertMessageSendStateConflict(match);
    }

    @Test
    @DisplayName("공백뿐인 메시지와 100자 초과 메시지는 400")
    void 잘못된_메시지_내용_거부() throws Exception {
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
    @DisplayName("음수 afterId 조회 요청은 400")
    void 음수_afterId_거부() throws Exception {
        TestMatch match = createMatch(ActivityMatchStatus.CONFIRMED, false);

        mockMvc.perform(get("/api/matching/matches/{activityMatchId}/messages", match.activityMatchId())
                        .param("afterId", "-1")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(match.hostId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("afterId는 0 이상이어야 합니다."));
    }

    @Test
    @DisplayName("숫자가 아닌 afterId는 공통 ErrorResponse 형식의 400")
    void 형식_오류_afterId_거부() throws Exception {
        TestMatch match = createMatch(ActivityMatchStatus.CONFIRMED, false);

        mockMvc.perform(get("/api/matching/matches/{activityMatchId}/messages", match.activityMatchId())
                        .param("afterId", "not-a-number")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(match.hostId())))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value("요청 형식이 올바르지 않습니다."));
    }

    @Test
    @DisplayName("토큰 없는 메시지 조회는 401")
    void 인증_없는_메시지_조회_거부() throws Exception {
        TestMatch match = createMatch(ActivityMatchStatus.CONFIRMED, false);

        mockMvc.perform(get("/api/matching/matches/{activityMatchId}/messages", match.activityMatchId()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
    }

    private void assertMessageSendCreated(TestMatch match, String message) throws Exception {
        mockMvc.perform(post("/api/matching/matches/{activityMatchId}/messages", match.activityMatchId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(match.hostId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "%s"}
                                """.formatted(message)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.content").value(message));
    }

    private void assertMessageSendTimeConflict(TestMatch match) throws Exception {
        mockMvc.perform(post("/api/matching/matches/{activityMatchId}/messages", match.activityMatchId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(match.hostId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "종료 후 메시지"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("채팅 가능 시간이 지나 새 메시지를 보낼 수 없어요."));
    }

    private void assertMessageSendStateConflict(TestMatch match) throws Exception {
        mockMvc.perform(post("/api/matching/matches/{activityMatchId}/messages", match.activityMatchId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(match.hostId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "취소 후 메시지"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("현재 매칭 상태에서는 새 메시지를 보낼 수 없어요."));
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

        OffsetDateTime now = OffsetDateTime.now(clock);
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

    // 활동 예정일 경계 테스트에서 서버 현재 시각과 무관한 시작·종료 시각을 직접 지정
    private TestMatch createMatch(
            ActivityMatchStatus status,
            OffsetDateTime scheduledAt,
            OffsetDateTime scheduledEndAt
    ) {
        Long hostId = createUser("채팅호스트");
        Long applicantId = createUser("채팅신청자");
        Long outsiderId = createUser("채팅제삼자");

        OffsetDateTime createdAt = scheduledAt.minusDays(1);
        OffsetDateTime confirmedAt = createdAt.plusMinutes(30);
        OffsetDateTime closedAt = status == ActivityMatchStatus.ENDED ? scheduledEndAt : null;

        Long activityMatchId = jdbcTemplate.queryForObject(
                """
                INSERT INTO activity_match (
                    scheduled_at, scheduled_end_at, talk_level, location, course_name,
                    distance_min_meters, distance_max_meters, route_description,
                    agreed_pace_min_sec, agreed_pace_max_sec, status,
                    decision_expires_at, meeting_code, confirmed_at, closed_at, created_at
                )
                VALUES (?, ?, 'SILENT', '테스트 장소', '채팅 테스트 코스',
                        5000, 8000, '테스트 경로', 360, 400, ?, ?, '123456', ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                scheduledAt,
                scheduledEndAt,
                status.name(),
                createdAt.plusHours(1),
                confirmedAt,
                closedAt,
                createdAt
        );

        OffsetDateTime releasedAt = status == ActivityMatchStatus.ENDED ? closedAt : null;
        createParticipant(activityMatchId, hostId, "A", "ACCEPTED", releasedAt);
        createParticipant(activityMatchId, applicantId, "B", "ACCEPTED", releasedAt);

        return new TestMatch(activityMatchId, hostId, applicantId, outsiderId);
    }

    private void setCurrentTime(OffsetDateTime currentTime) {
        when(clock.instant()).thenReturn(currentTime.toInstant());
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
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
