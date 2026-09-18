package com.team4.hanbbyeom.feedback;

import com.team4.hanbbyeom.feedback.domain.NoShowReason;
import com.team4.hanbbyeom.feedback.dto.NoShowReportCreateRequest;
import com.team4.hanbbyeom.feedback.dto.ReviewCreateRequest;
import com.team4.hanbbyeom.global.security.jwt.JwtTokenProvider;
import com.team4.hanbbyeom.matching.domain.AcceptStatus;
import com.team4.hanbbyeom.matching.domain.ActivityMatch;
import com.team4.hanbbyeom.matching.domain.MatchParticipant;
import com.team4.hanbbyeom.matching.domain.MatchRequest;
import com.team4.hanbbyeom.matching.domain.TalkLevel;
import com.team4.hanbbyeom.matching.repository.ActivityMatchRepository;
import com.team4.hanbbyeom.matching.repository.MatchParticipantRepository;
import com.team4.hanbbyeom.matching.repository.MatchRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

// #68: 활동 후기 작성 및 노쇼 신고 API 구현 — MockMvc + JWT로 실제 HTTP 요청을 보내
// Controller 매핑, JWT 인증, Bean Validation, GlobalExceptionHandler 매핑까지 검증한다.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
public class ActivityFeedbackApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    // RunApiIntegrationTest와 동일한 이유로 tools.jackson(Jackson 3) 사용
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private ActivityMatchRepository activityMatchRepository;
    @Autowired
    private MatchRequestRepository matchRequestRepository;
    @Autowired
    private MatchParticipantRepository matchParticipantRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userAId; // 활동의 참가자 A
    private Long userBId; // 활동의 참가자 B
    private Long endedActivityMatchId; // 이미 끝난(ENDED) 매칭 — 대부분의 테스트가 이걸 씀

    @BeforeEach
    void setUp() {
        userAId = createUser("usera");
        userBId = createUser("userb");
        endedActivityMatchId = createActivityMatch(userAId, userBId, true);
    }

    private Long createUser(String label) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, nickname, email_verified_at) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class,
                label + "-" + System.nanoTime() + "@example.com", "dummy-hash", label, OffsetDateTime.now()
        );
    }

    private String bearerToken(Long userId) {
        return "Bearer " + jwtTokenProvider.createAccessToken(userId);
    }

    // ended=true면 confirm()+end()까지 호출해서 "이미 끝난 활동"으로, false면 CONFIRMED만
    // 시켜서 "아직 안 끝난 활동"으로 만든다. ActivityMatch 생성자가 createdAt을 직접 받게
    // 바뀐 덕분에(PR #66), 시간이 실제로 지나가길 기다릴 필요 없이 처음부터 과거 시각으로
    // 만들 수 있다 — chk_activity_match_time은 네 시각 필드의 상대 순서만 검증하기 때문.
    private Long createActivityMatch(Long userA, Long userB, boolean ended) {
        OffsetDateTime base = ended ? OffsetDateTime.now().minusHours(2) : OffsetDateTime.now();

        ActivityMatch activityMatch = new ActivityMatch(
                base.plusMinutes(20), base.plusMinutes(30), TalkLevel.SILENT,
                "뚝섬 한강공원", "뚝섬 한강공원 코스", 5000, 12000,
                "뚝섬유원지역 3번 출구", 360, 400,
                base.plusMinutes(10), base
        );
        activityMatch.confirm("123456");
        if (ended) {
            activityMatch.end();
        }
        Long activityMatchId = activityMatchRepository.save(activityMatch).getId();

        // match_participant는 match_request(id, user_id) 복합 FK가 있어서 각자 본인 소유
        // match_request가 먼저 필요 — 이건 실제 "지금"보다 미래여야 하는 별개 제약이라
        // activity_match의 과거 타임라인과 무관하게 항상 미래로 만든다.
        Long requestAId = matchRequestRepository.save(
                new MatchRequest(userA, OffsetDateTime.now().plusMinutes(20), TalkLevel.SILENT, OffsetDateTime.now().plusMinutes(15))
        ).getId();
        Long requestBId = matchRequestRepository.save(
                new MatchRequest(userB, OffsetDateTime.now().plusMinutes(20), TalkLevel.SILENT, OffsetDateTime.now().plusMinutes(15))
        ).getId();

        matchParticipantRepository.save(new MatchParticipant(activityMatchId, requestAId, userA, "A", AcceptStatus.ACCEPTED));
        matchParticipantRepository.save(new MatchParticipant(activityMatchId, requestBId, userB, "B", AcceptStatus.ACCEPTED));

        return activityMatchId;
    }

    // 1. 정상 플로우: 제출 전 canSubmit=true 확인 -> 후기 등록 -> trust_profile 반영 확인
    //    -> 제출 후 alreadySubmitted=true로 바뀌는지 확인
    @Test
    void 정상적으로_후기를_작성하면_신뢰도에_반영된다() throws Exception {
        mockMvc.perform(get("/api/matching/matches/{id}/feedback-status", endedActivityMatchId)
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(userAId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canSubmit").value(true))
                .andExpect(jsonPath("$.alreadySubmitted").value(false));

        ReviewCreateRequest request = new ReviewCreateRequest(5, TalkLevel.LIGHT_CHAT, "좋았어요");
        mockMvc.perform(post("/api/matching/matches/{id}/review", endedActivityMatchId)
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(userAId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNoContent());

        // userA가 userB(상대)에 대해 남긴 후기이므로, trust_profile은 userB 기준으로 갱신되어야 함
        var profile = jdbcTemplate.queryForMap(
                "SELECT average_rating, review_count, completed_activity_count, review_light_chat_vote_count " +
                        "FROM trust_profile WHERE user_id = ?", userBId);
        assertThat(((Number) profile.get("average_rating")).doubleValue()).isEqualTo(5.0);
        assertThat(profile.get("review_count")).isEqualTo(1);
        assertThat(profile.get("completed_activity_count")).isEqualTo(1);
        assertThat(profile.get("review_light_chat_vote_count")).isEqualTo(1);

        mockMvc.perform(get("/api/matching/matches/{id}/feedback-status", endedActivityMatchId)
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(userAId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadySubmitted").value(true))
                .andExpect(jsonPath("$.submittedType").value("REVIEW"));
    }

    // 2. 같은 활동에 대해 두 번째 제출 시도하면 409
    @Test
    void 중복_제출하면_409를_반환한다() throws Exception {
        ReviewCreateRequest request = new ReviewCreateRequest(4, TalkLevel.SILENT, null);
        mockMvc.perform(post("/api/matching/matches/{id}/review", endedActivityMatchId)
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(userAId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/matching/matches/{id}/review", endedActivityMatchId)
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(userAId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
    }

    // 3. 아직 끝나지 않은 활동에 후기를 남기려 하면 400
    @Test
    void 아직_끝나지_않은_활동은_400을_반환한다() throws Exception {
        Long userCId = createUser("userc");
        Long userDId = createUser("userd");
        Long notEndedId = createActivityMatch(userCId, userDId, false);

        ReviewCreateRequest request = new ReviewCreateRequest(5, TalkLevel.SILENT, null);
        mockMvc.perform(post("/api/matching/matches/{id}/review", notEndedId)
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(userCId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    // 4. 이 매칭과 무관한 제3자가 시도하면 403
    @Test
    void 참가자가_아니면_403을_반환한다() throws Exception {
        Long strangerId = createUser("stranger");

        ReviewCreateRequest request = new ReviewCreateRequest(5, TalkLevel.SILENT, null);
        mockMvc.perform(post("/api/matching/matches/{id}/review", endedActivityMatchId)
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(strangerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    // 5. 노쇼 신고 접수 -> 신고당한 상대(userB)의 trust_profile.no_show_report_count 반영 확인
    @Test
    void 노쇼_신고하면_상대방_신뢰도에_반영된다() throws Exception {
        NoShowReportCreateRequest request = new NoShowReportCreateRequest(NoShowReason.NOT_SHOWED_UP, "연락 두절");
        mockMvc.perform(post("/api/matching/matches/{id}/no-show-report", endedActivityMatchId)
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(userAId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNoContent());

        Integer noShowCount = jdbcTemplate.queryForObject(
                "SELECT no_show_report_count FROM trust_profile WHERE user_id = ?", Integer.class, userBId);
        assertThat(noShowCount).isEqualTo(1);
    }
}