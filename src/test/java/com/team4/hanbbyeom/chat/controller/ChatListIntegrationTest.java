package com.team4.hanbbyeom.chat.controller;

import com.team4.hanbbyeom.global.security.jwt.JwtTokenProvider;
import com.team4.hanbbyeom.matching.domain.ActivityMatchStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 실제 PostgreSQL 조회와 JWT 인증을 거쳐 채팅 목록·매칭 상세 응답을 검증
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ChatListIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Test
    void 채팅_목록을_최근_메시지_시각_순으로_조회하고_메시지_없는_매칭도_포함한다() throws Exception {
        Long currentUserId = createUser("현재사용자");
        Long endedCounterpartId = createUser("종료매칭상대");
        Long currentCounterpartId = createUser("현재매칭상대");
        OffsetDateTime now = OffsetDateTime.now();

        // 종료된 과거 매칭이지만 최근 메시지가 있어 목록의 첫 번째 항목이 되는 상황
        Long endedMatchId = createMatch(
                currentUserId,
                endedCounterpartId,
                ActivityMatchStatus.ENDED,
                "뚝섬 한강공원",
                "뚝섬유원지역 3번 출구",
                now.minusHours(4),
                now.minusHours(1),
                now.minusMinutes(30)
        );
        createMessage(endedMatchId, endedCounterpartId, "조심히 들어가세요.", now.minusHours(2));

        // 메시지는 없지만 확정 이력이 있으므로 confirmedAt을 정렬 기준으로 목록에 포함
        Long currentMatchId = createMatch(
                currentUserId,
                currentCounterpartId,
                ActivityMatchStatus.CONFIRMED,
                "여의도 한강공원",
                "여의나루역 2번 출구",
                now.minusHours(3),
                now.plusHours(1),
                now.plusHours(2)
        );

        mockMvc.perform(get("/api/chats")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(currentUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].activityMatchId").value(endedMatchId))
                .andExpect(jsonPath("$[0].counterpartUserId").value(endedCounterpartId))
                .andExpect(jsonPath("$[0].status").value("ENDED"))
                .andExpect(jsonPath("$[0].lastMessage").value("조심히 들어가세요."))
                .andExpect(jsonPath("$[0].lastMessageAt").isNotEmpty())
                .andExpect(jsonPath("$[1].activityMatchId").value(currentMatchId))
                .andExpect(jsonPath("$[1].counterpartUserId").value(currentCounterpartId))
                .andExpect(jsonPath("$[1].status").value("CONFIRMED"))
                .andExpect(jsonPath("$[1].lastMessage").isEmpty())
                .andExpect(jsonPath("$[1].lastMessageAt").isEmpty());
    }

    @Test
    void 확정되지_않은_매칭은_채팅_목록에_포함하지_않는다() throws Exception {
        Long hostId = createUser("대기호스트");
        Long applicantId = createUser("대기신청자");
        OffsetDateTime now = OffsetDateTime.now();

        createMatch(
                hostId,
                applicantId,
                ActivityMatchStatus.PROPOSED,
                "잠실 한강공원",
                "잠실나루역 1번 출구",
                null,
                now.plusHours(2),
                now.plusHours(3)
        );

        mockMvc.perform(get("/api/chats")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(hostId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void 매칭_상세에서_상대_사용자와_채팅방_표시_정보를_함께_반환한다() throws Exception {
        Long hostId = createUser("상세호스트");
        Long applicantId = createUser("상세신청자");
        OffsetDateTime now = OffsetDateTime.now();

        Long activityMatchId = createMatch(
                hostId,
                applicantId,
                ActivityMatchStatus.CONFIRMED,
                "반포 한강공원",
                "고속터미널역 8-1번 출구",
                now.minusMinutes(30),
                now.plusHours(1),
                now.plusHours(2)
        );

        mockMvc.perform(get("/api/matching/matches/{activityMatchId}", activityMatchId)
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(hostId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activityMatchId").value(activityMatchId))
                .andExpect(jsonPath("$.counterpartUserId").value(applicantId))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.courseName").value("반포 한강공원"))
                .andExpect(jsonPath("$.location").value("고속터미널역 8-1번 출구"))
                .andExpect(jsonPath("$.scheduledAt").isNotEmpty())
                .andExpect(jsonPath("$.scheduledEndAt").isNotEmpty());
    }

    @Test
    void 토큰_없는_채팅_목록_조회는_401로_거부한다() throws Exception {
        mockMvc.perform(get("/api/chats"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
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
                "chat-list-" + System.nanoTime() + "@example.com",
                nickname,
                OffsetDateTime.now()
        );
    }

    private Long createMatch(
            Long hostId,
            Long applicantId,
            ActivityMatchStatus status,
            String courseName,
            String location,
            OffsetDateTime confirmedAt,
            OffsetDateTime scheduledAt,
            OffsetDateTime scheduledEndAt
    ) {
        OffsetDateTime createdAt = (confirmedAt == null ? OffsetDateTime.now() : confirmedAt)
                .minusHours(1);
        OffsetDateTime decisionExpiresAt = scheduledAt.minusMinutes(30);
        boolean confirmedBefore = confirmedAt != null;
        String meetingCode = confirmedBefore ? "123456" : null;
        OffsetDateTime closedAt = status == ActivityMatchStatus.ENDED ? scheduledEndAt : null;

        Long activityMatchId = jdbcTemplate.queryForObject(
                """
                INSERT INTO activity_match (
                    scheduled_at, scheduled_end_at, talk_level, location, course_name,
                    distance_min_meters, distance_max_meters, route_description,
                    agreed_pace_min_sec, agreed_pace_max_sec, status,
                    decision_expires_at, meeting_code, confirmed_at, closed_at, created_at
                )
                VALUES (?, ?, 'SILENT', ?, ?, 5000, 8000, '테스트 경로',
                        360, 400, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                scheduledAt,
                scheduledEndAt,
                location,
                courseName,
                status.name(),
                decisionExpiresAt,
                meetingCode,
                confirmedAt,
                closedAt,
                createdAt
        );

        OffsetDateTime releasedAt = status == ActivityMatchStatus.ENDED ? closedAt : null;
        String hostAcceptStatus = status == ActivityMatchStatus.PROPOSED ? "PENDING" : "ACCEPTED";
        createParticipant(activityMatchId, hostId, "A", hostAcceptStatus, releasedAt);
        createParticipant(activityMatchId, applicantId, "B", "ACCEPTED", releasedAt);

        return activityMatchId;
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

    private void createMessage(
            Long activityMatchId,
            Long senderId,
            String content,
            OffsetDateTime createdAt
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO chat_message (activity_match_id, sender_id, content, created_at)
                VALUES (?, ?, ?, ?)
                """,
                activityMatchId,
                senderId,
                content,
                createdAt
        );
    }
}
