package com.team4.hanbbyeom.matching;

import com.team4.hanbbyeom.feedback.domain.ActivityReview;
import com.team4.hanbbyeom.feedback.domain.NoShowReason;
import com.team4.hanbbyeom.feedback.domain.NoShowReport;
import com.team4.hanbbyeom.feedback.repository.ActivityReviewRepository;
import com.team4.hanbbyeom.feedback.repository.NoShowReportRepository;
import com.team4.hanbbyeom.global.security.jwt.JwtTokenProvider;
import com.team4.hanbbyeom.matching.domain.TalkLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 내 활동 이력 목록(GET /api/matching/matches)을 실제 JWT 인증·Controller·Service·DB 흐름으로 검증한다.
// 활동 시각·상태를 정확히 통제하려고 activity_match와 match_participant는 SQL로 직접 만든다.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MyActivitiesIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private ActivityReviewRepository activityReviewRepository;
    @Autowired private NoShowReportRepository noShowReportRepository;

    private Long createUser(String nickname) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO users (email, password_hash, nickname, email_verified_at)
                VALUES (?, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                "activities-" + UUID.randomUUID() + "@example.com", "dummy-hash", nickname, OffsetDateTime.now()
        );
    }

    private String bearerToken(Long userId) {
        return "Bearer " + jwtTokenProvider.createAccessToken(userId);
    }

    // 지정한 상태의 activity_match와 참가자 2명(호스트 A, 신청자 B)을 만든다.
    // chk_activity_match_time(created_at < decision_expires_at < scheduled_at < scheduled_end_at)을 지키도록
    // 생성·응답 기한은 활동 시작보다 앞선 값으로 둔다.
    // 확정된 적 있는 매칭(CONFIRMED/ENDED/CANCELLED)만 confirmed_at을 채운다. uq_participant_active_user 때문에
    // 사용자당 활성(released_at IS NULL) 참가는 하나뿐이어야 하므로, 아직 진행 중인 CONFIRMED/PROPOSED만
    // 참가를 해제하지 않는다.
    private Long createMatch(Long hostId, Long applicantId, String status, String courseName,
                             OffsetDateTime scheduledAt) {
        boolean everConfirmed = status.equals("CONFIRMED") || status.equals("ENDED") || status.equals("CANCELLED");
        boolean stillActive = status.equals("CONFIRMED") || status.equals("PROPOSED");

        Long matchId = jdbcTemplate.queryForObject(
                """
                INSERT INTO activity_match
                    (scheduled_at, scheduled_end_at, talk_level, location, course_name,
                     distance_min_meters, distance_max_meters, route_description,
                     agreed_pace_min_sec, agreed_pace_max_sec, status,
                     decision_expires_at, meeting_code, confirmed_at, closed_at, created_at)
                VALUES (?, ?, 'LIGHT_CHAT', '만나는 곳', ?, 5000, 12000, '코스 설명', 360, 400, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                scheduledAt, scheduledAt.plusHours(2), courseName, status,
                scheduledAt.minusDays(2),
                everConfirmed ? "123456" : null,
                everConfirmed ? scheduledAt.minusDays(1) : null,
                stillActive ? null : scheduledAt.plusHours(3),
                scheduledAt.minusDays(3)
        );

        OffsetDateTime releasedAt = stillActive ? null : scheduledAt.plusHours(3);
        insertParticipant(matchId, hostId, "A", releasedAt);
        insertParticipant(matchId, applicantId, "B", releasedAt);
        return matchId;
    }

    private void insertParticipant(Long matchId, Long userId, String slot, OffsetDateTime releasedAt) {
        jdbcTemplate.update(
                """
                INSERT INTO match_participant (activity_match_id, user_id, slot, accept_status, released_at)
                VALUES (?, ?, ?, 'ACCEPTED', ?)
                """,
                matchId, userId, slot, releasedAt
        );
    }

    // chk_users_account_lifecycle: 개인정보 전부 NULL + deleted_at 기록
    private void withdrawUser(Long userId) {
        jdbcTemplate.update(
                """
                UPDATE users
                SET email = NULL, password_hash = NULL, nickname = NULL,
                    email_verified_at = NULL, default_talk_level = NULL, deleted_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                userId
        );
    }

    @Test
    @DisplayName("확정된 적 있는 매칭(CONFIRMED/ENDED/CANCELLED)을 활동 시작 시각 최신순으로 원본 상태 그대로 반환한다")
    void 확정된_적_있는_매칭을_최신순으로_반환한다() throws Exception {
        Long me = createUser("나");
        Long partner = createUser("조용한러너");
        OffsetDateTime now = OffsetDateTime.now();
        createMatch(partner, me, "ENDED", "잠실 한강공원", now.minusDays(10));
        createMatch(partner, me, "CANCELLED", "반포 한강공원", now.minusDays(5));
        createMatch(partner, me, "CONFIRMED", "뚝섬 한강공원", now.plusDays(3)); // 아직 시작 전, 활성 참가

        mockMvc.perform(get("/api/matching/matches")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                // 활동 시작 시각 최신순
                .andExpect(jsonPath("$[0].courseName").value("뚝섬 한강공원"))
                .andExpect(jsonPath("$[0].status").value("CONFIRMED"))
                .andExpect(jsonPath("$[1].courseName").value("반포 한강공원"))
                .andExpect(jsonPath("$[1].status").value("CANCELLED"))
                // 취소 주체는 CANCELLED에만 있다. 이 매칭은 사용자 행동 없이 시스템이 취소했다(closed_by_user_id NULL)
                .andExpect(jsonPath("$[1].cancelledBy").value("SYSTEM"))
                .andExpect(jsonPath("$[0].cancelledBy").value(nullValue()))
                .andExpect(jsonPath("$[2].cancelledBy").value(nullValue()))
                .andExpect(jsonPath("$[2].courseName").value("잠실 한강공원"))
                .andExpect(jsonPath("$[2].status").value("ENDED"))
                // 화면에 필요한 나머지 필드
                .andExpect(jsonPath("$[0].activityMatchId").isNumber())
                .andExpect(jsonPath("$[0].distanceMinMeters").value(5000))
                .andExpect(jsonPath("$[0].distanceMaxMeters").value(12000))
                .andExpect(jsonPath("$[0].scheduledAt").exists())
                .andExpect(jsonPath("$[0].scheduledEndAt").exists())
                .andExpect(jsonPath("$[0].counterpartNickname").value("조용한러너"));
    }

    // 지금은 회원 탈퇴가 유일한 CANCELLED 경로(closed_by_user_id NULL)이지만, 나중에 확정 매칭을 사용자가 직접
    // 취소하는 기능이 생기면 그 사용자의 id가 closed_by_user_id에 남는다(스키마 정의). 그 경로를 SQL로 재현해
    // 응답이 취소 주체를 구분하는지 확인한다 — 프론트가 counterpartNickname 등으로 추론하지 않아도 되도록.
    @Test
    @DisplayName("CANCELLED의 취소 주체를 내 시점으로 구분한다(ME / COUNTERPART / SYSTEM), 그 외 상태는 null")
    void 취소_주체를_구분한다() throws Exception {
        Long me = createUser("나");
        Long partner = createUser("상대");
        OffsetDateTime now = OffsetDateTime.now();
        createMatch(partner, me, "CANCELLED", "시스템 취소", now.minusDays(30));
        Long cancelledByMe = createMatch(partner, me, "CANCELLED", "내가 취소", now.minusDays(20));
        Long cancelledByPartner = createMatch(partner, me, "CANCELLED", "상대가 취소", now.minusDays(10));
        createMatch(partner, me, "ENDED", "종료", now.minusDays(5));
        jdbcTemplate.update("UPDATE activity_match SET closed_by_user_id = ? WHERE id = ?", me, cancelledByMe);
        jdbcTemplate.update("UPDATE activity_match SET closed_by_user_id = ? WHERE id = ?", partner, cancelledByPartner);

        mockMvc.perform(get("/api/matching/matches")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[0].courseName").value("종료"))
                .andExpect(jsonPath("$[0].cancelledBy").value(nullValue()))
                .andExpect(jsonPath("$[1].courseName").value("상대가 취소"))
                .andExpect(jsonPath("$[1].cancelledBy").value("COUNTERPART"))
                .andExpect(jsonPath("$[2].courseName").value("내가 취소"))
                .andExpect(jsonPath("$[2].cancelledBy").value("ME"))
                .andExpect(jsonPath("$[3].courseName").value("시스템 취소"))
                .andExpect(jsonPath("$[3].cancelledBy").value("SYSTEM"));

        // 같은 매칭을 상대 시점에서 보면 주체가 뒤바뀐다
        mockMvc.perform(get("/api/matching/matches")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(partner)))
                .andExpect(jsonPath("$[1].courseName").value("상대가 취소"))
                .andExpect(jsonPath("$[1].cancelledBy").value("ME"))
                .andExpect(jsonPath("$[2].courseName").value("내가 취소"))
                .andExpect(jsonPath("$[2].cancelledBy").value("COUNTERPART"));
    }

    @Test
    @DisplayName("확정된 적 없는 매칭(PROPOSED/REJECTED/EXPIRED)은 이력에서 제외한다")
    void 확정된_적_없는_매칭은_제외한다() throws Exception {
        Long me = createUser("나");
        Long partner = createUser("상대");
        OffsetDateTime now = OffsetDateTime.now();
        createMatch(partner, me, "REJECTED", "거절된 코스", now.plusDays(1));
        createMatch(partner, me, "EXPIRED", "만료된 코스", now.plusDays(2));
        createMatch(partner, me, "PROPOSED", "대기 중 코스", now.plusDays(3)); // 신청 대기 — 아직 확정 전

        mockMvc.perform(get("/api/matching/matches")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("다른 사용자의 이력은 조회되지 않고, 호스트·신청자 어느 쪽이든 상대 닉네임이 내려온다")
    void 본인의_이력만_조회되고_상대_닉네임이_내려온다() throws Exception {
        Long host = createUser("호스트닉");
        Long applicant = createUser("신청자닉");
        Long stranger = createUser("무관한사람");
        Long strangerPartner = createUser("무관한상대");
        OffsetDateTime now = OffsetDateTime.now();
        createMatch(host, applicant, "ENDED", "우리 코스", now.minusDays(2));
        createMatch(strangerPartner, stranger, "ENDED", "남의 코스", now.minusDays(2));

        // 호스트가 보면 상대는 신청자
        mockMvc.perform(get("/api/matching/matches")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(host)))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].courseName").value("우리 코스"))
                .andExpect(jsonPath("$[0].counterpartNickname").value("신청자닉"));
        // 신청자가 보면 상대는 호스트
        mockMvc.perform(get("/api/matching/matches")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(applicant)))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].counterpartNickname").value("호스트닉"));
    }

    @Test
    @DisplayName("후기·노쇼 신고를 제출하지 않은 활동은 null, 제출했으면 REVIEW/NO_SHOW_REPORT로 내려온다")
    void 후기_노쇼_신고_제출_여부가_반영된다() throws Exception {
        Long me = createUser("나");
        Long partner = createUser("상대");
        OffsetDateTime now = OffsetDateTime.now();
        Long notSubmitted = createMatch(partner, me, "ENDED", "미제출", now.minusDays(30));
        Long reviewed = createMatch(partner, me, "ENDED", "후기 제출", now.minusDays(20));
        Long reported = createMatch(partner, me, "ENDED", "노쇼 신고", now.minusDays(10));
        // 상대가 나에 대해 후기를 남긴 것은 내 제출 여부에 영향을 주지 않는다
        activityReviewRepository.save(new ActivityReview(notSubmitted, partner, me, 4, TalkLevel.SILENT, null));
        activityReviewRepository.save(new ActivityReview(reviewed, me, partner, 5, TalkLevel.LIGHT_CHAT, "좋았어요"));
        noShowReportRepository.save(new NoShowReport(reported, me, partner, NoShowReason.NOT_SHOWED_UP, null));

        mockMvc.perform(get("/api/matching/matches")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].courseName").value("노쇼 신고"))
                .andExpect(jsonPath("$[0].submittedFeedbackType").value("NO_SHOW_REPORT"))
                .andExpect(jsonPath("$[1].courseName").value("후기 제출"))
                .andExpect(jsonPath("$[1].submittedFeedbackType").value("REVIEW"))
                .andExpect(jsonPath("$[2].courseName").value("미제출"))
                .andExpect(jsonPath("$[2].submittedFeedbackType").value(nullValue()));
    }

    @Test
    @DisplayName("상대가 탈퇴한 활동은 이력에 유지되고 counterpartNickname만 null이다")
    void 상대가_탈퇴해도_항목이_유지되고_닉네임만_null이다() throws Exception {
        Long me = createUser("나");
        Long partner = createUser("탈퇴할상대");
        createMatch(partner, me, "ENDED", "탈퇴 상대 코스", OffsetDateTime.now().minusDays(3));
        withdrawUser(partner);

        mockMvc.perform(get("/api/matching/matches")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].courseName").value("탈퇴 상대 코스"))
                .andExpect(jsonPath("$[0].counterpartNickname").value(nullValue()));
    }

    @Test
    @DisplayName("이력이 없는 사용자는 빈 배열을 받는다")
    void 이력이_없으면_빈_배열을_반환한다() throws Exception {
        Long me = createUser("신규");

        mockMvc.perform(get("/api/matching/matches")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("인증 없이 조회하면 401이 반환된다")
    void 인증_없이_조회하면_401을_반환한다() throws Exception {
        mockMvc.perform(get("/api/matching/matches"))
                .andExpect(status().isUnauthorized());
    }
}
