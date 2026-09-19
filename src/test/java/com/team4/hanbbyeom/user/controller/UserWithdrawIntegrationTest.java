package com.team4.hanbbyeom.user.controller;

import com.team4.hanbbyeom.chat.domain.ChatMessage;
import com.team4.hanbbyeom.chat.repository.ChatMessageRepository;
import com.team4.hanbbyeom.global.security.jwt.JwtTokenProvider;
import com.team4.hanbbyeom.matching.domain.ActivityMatch;
import com.team4.hanbbyeom.matching.domain.ActivityMatchStatus;
import com.team4.hanbbyeom.matching.domain.MatchRequest;
import com.team4.hanbbyeom.matching.domain.MatchRequestStatus;
import com.team4.hanbbyeom.matching.domain.TalkLevel;
import com.team4.hanbbyeom.matching.repository.ActivityMatchRepository;
import com.team4.hanbbyeom.matching.repository.MatchParticipantRepository;
import com.team4.hanbbyeom.matching.repository.MatchRequestRepository;
import com.team4.hanbbyeom.matching.service.MatchApplyService;
import com.team4.hanbbyeom.matching.service.MatchDecisionService;
import com.team4.hanbbyeom.user.domain.DefaultTalkLevel;
import com.team4.hanbbyeom.user.domain.User;
import com.team4.hanbbyeom.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 회원 탈퇴(POST /api/users/me/withdraw) API를 실제 JWT 인증·Controller·Service·DB 흐름으로 검증
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserWithdrawIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private MatchRequestRepository matchRequestRepository;
    @Autowired private ActivityMatchRepository activityMatchRepository;
    @Autowired private MatchParticipantRepository matchParticipantRepository;
    @Autowired private ChatMessageRepository chatMessageRepository;
    @Autowired private MatchApplyService matchApplyService;
    @Autowired private MatchDecisionService matchDecisionService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private PasswordEncoder passwordEncoder;
    @PersistenceContext private EntityManager entityManager;

    private static final String PASSWORD = "password1234";

    // 비밀번호 재확인이 실제 BCrypt 비교로 이뤄지므로 해시를 진짜로 만들어 저장
    private User createUser(String email) {
        return userRepository.saveAndFlush(new User(
                email, passwordEncoder.encode(PASSWORD), "테스트사용자", DefaultTalkLevel.SILENT, Instant.now()
        ));
    }

    private String withdrawBody(String password) {
        return "{\"password\": \"" + password + "\"}";
    }

    private ResultActions withdraw(String token, String password) throws Exception {
        return mockMvc.perform(post("/api/users/me/withdraw")
                .header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(withdrawBody(password)));
    }

    private String bearerToken(Long userId) {
        return "Bearer " + jwtTokenProvider.createAccessToken(userId);
    }

    @Test
    @DisplayName("탈퇴하면 개인정보가 전부 NULL로 지워지고 탈퇴 시각이 기록된다")
    void 탈퇴하면_개인정보가_모두_제거된다() throws Exception {
        User user = createUser("withdraw-" + UUID.randomUUID() + "@example.com");

        withdraw(bearerToken(user.getId()), PASSWORD)
                .andExpect(status().isNoContent());
        entityManager.flush();

        User withdrawn = userRepository.findById(user.getId()).orElseThrow();
        assertThat(withdrawn.getEmail()).isNull();
        assertThat(withdrawn.getPasswordHash()).isNull();
        assertThat(withdrawn.getEmailVerifiedAt()).isNull();
        assertThat(withdrawn.getNickname()).isNull();
        assertThat(withdrawn.getDefaultTalkLevel()).isNull();
        assertThat(withdrawn.getDeletedAt()).isNotNull();
    }

    @Test
    @DisplayName("탈퇴 후 같은 이메일로 다시 가입할 수 있다")
    void 탈퇴_후_같은_이메일로_재가입할_수_있다() throws Exception {
        String email = "rejoin-" + UUID.randomUUID() + "@example.com";
        User user = createUser(email);

        withdraw(bearerToken(user.getId()), PASSWORD)
                .andExpect(status().isNoContent());
        // withdraw()의 email UPDATE가 아직 DB에 반영 안 된 상태일 수 있는데, User가 IDENTITY
        // 전략이라 바로 아래 saveAndFlush()의 INSERT가 그 UPDATE보다 먼저 실행돼버릴 수 있다
        // (실제 운영에서는 요청이 끝나며 트랜잭션이 커밋되니 문제없음 — 테스트에서만 필요).
        entityManager.flush();

        // UNIQUE(email) 제약은 NULL끼리는 중복으로 안 치므로, 탈퇴한 계정과 같은 이메일로
        // 새 활성 계정을 만들어도 제약 위반이 나면 안 된다.
        User newUser = userRepository.saveAndFlush(new User(
                email, "encoded-password", "새사용자", DefaultTalkLevel.LIGHT_CHAT, Instant.now()
        ));

        assertThat(newUser.getId()).isNotEqualTo(user.getId());
    }

    @Test
    @DisplayName("탈퇴 후 기존 access token으로 요청하면 401이 반환된다")
    void 탈퇴_후_기존_토큰으로_요청하면_401을_반환한다() throws Exception {
        User user = createUser("token-" + UUID.randomUUID() + "@example.com");
        String token = bearerToken(user.getId());

        withdraw(token, PASSWORD)
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("탈퇴하면 남아 있던 이메일 인증 기록도 함께 삭제된다")
    void 탈퇴하면_이메일_인증_기록도_삭제된다() throws Exception {
        String email = "verified-" + UUID.randomUUID() + "@example.com";
        User user = createUser(email);
        jdbcTemplate.update(
                """
                INSERT INTO email_verifications (email, purpose, code_hash, expires_at, verified_at)
                VALUES (?, 'SIGNUP', 'dummy-hash', now() + interval '1 hour', now())
                """,
                email
        );

        withdraw(bearerToken(user.getId()), PASSWORD)
                .andExpect(status().isNoContent());
        // deleteByEmail()의 삭제가 아직 DB에 반영 안 된 상태일 수 있는데, 바로 아래 검증은
        // raw JDBC라 Hibernate 세션을 안 거쳐서 flush 없이는 옛날 값을 보게 된다.
        entityManager.flush();

        Integer remaining = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM email_verifications WHERE email = ?", Integer.class, email);
        assertThat(remaining).isZero();
    }

    @Test
    @DisplayName("비밀번호가 틀리면 403으로 거부하고 계정은 그대로 유지된다")
    void 비밀번호가_틀리면_탈퇴가_거부된다() throws Exception {
        User user = createUser("wrong-pw-" + UUID.randomUUID() + "@example.com");
        String token = bearerToken(user.getId());

        withdraw(token, "not-my-password")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("비밀번호가 올바르지 않습니다."));
        entityManager.flush();
        entityManager.clear();

        User unchanged = userRepository.findById(user.getId()).orElseThrow();
        assertThat(unchanged.getDeletedAt()).isNull();
        assertThat(unchanged.getEmail()).isEqualTo(user.getEmail());

        // 거부 이후에도 같은 토큰이 계속 유효해야 한다(401이 아니라 403인 이유)
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("비밀번호가 비어 있거나 바디가 없으면 400으로 거부하고 계정은 유지된다")
    void 비밀번호가_없으면_400을_반환한다() throws Exception {
        User user = createUser("blank-pw-" + UUID.randomUUID() + "@example.com");
        String token = bearerToken(user.getId());

        withdraw(token, "").andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/users/me/withdraw")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/users/me/withdraw")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isBadRequest());

        entityManager.flush();
        entityManager.clear();
        assertThat(userRepository.findById(user.getId()).orElseThrow().getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("토큰 없이 탈퇴를 요청하면 401이 반환된다")
    void 토큰_없이_탈퇴하면_401을_반환한다() throws Exception {
        mockMvc.perform(post("/api/users/me/withdraw")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withdrawBody(PASSWORD)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("다른 사용자의 비밀번호로는 탈퇴할 수 없다")
    void 다른_사용자의_비밀번호로는_탈퇴할_수_없다() throws Exception {
        User me = createUser("me-" + UUID.randomUUID() + "@example.com");
        User other = userRepository.saveAndFlush(new User(
                "other-" + UUID.randomUUID() + "@example.com",
                passwordEncoder.encode("other-password99"),
                "다른사용자", DefaultTalkLevel.SILENT, Instant.now()
        ));

        // 내 토큰으로 다른 사용자의 비밀번호를 보내도 거부되고, 다른 사용자 계정도 영향받지 않는다
        withdraw(bearerToken(me.getId()), "other-password99")
                .andExpect(status().isForbidden());
        entityManager.flush();
        entityManager.clear();

        assertThat(userRepository.findById(me.getId()).orElseThrow().getDeletedAt()).isNull();
        assertThat(userRepository.findById(other.getId()).orElseThrow().getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("탈퇴 후 같은 이메일·비밀번호로 로그인하면 실패한다")
    void 탈퇴_후_로그인하면_실패한다() throws Exception {
        String email = "login-" + UUID.randomUUID() + "@example.com";
        User user = createUser(email);

        withdraw(bearerToken(user.getId()), PASSWORD).andExpect(status().isNoContent());
        entityManager.flush();

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"" + email + "\", \"password\": \"" + PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    private MatchRequest createMatchRequest(Long userId, MatchRequestStatus status) {
        MatchRequest request = matchRequestRepository.save(new MatchRequest(
                userId, OffsetDateTime.now().plusHours(48), TalkLevel.LIGHT_CHAT,
                OffsetDateTime.now().plusHours(9)
        ));
        request.changeStatus(status);
        matchRequestRepository.flush();
        return request;
    }

    @Test
    @DisplayName("탈퇴하면 본인의 모집 중(SEARCHING) 게시글이 CANCELLED로 바뀐다")
    void 탈퇴하면_모집_중_게시글이_취소된다() throws Exception {
        User user = createUser("cancel-" + UUID.randomUUID() + "@example.com");
        MatchRequest request = createMatchRequest(user.getId(), MatchRequestStatus.SEARCHING);

        withdraw(bearerToken(user.getId()), PASSWORD).andExpect(status().isNoContent());
        entityManager.flush();
        entityManager.clear();

        assertThat(matchRequestRepository.findById(request.getId()).orElseThrow().getStatus())
                .isEqualTo(MatchRequestStatus.CANCELLED);
    }

    @Test
    @DisplayName("비밀번호가 틀려 탈퇴가 거부되면 모집 중 게시글도 취소되지 않는다")
    void 탈퇴가_거부되면_게시글도_유지된다() throws Exception {
        User user = createUser("keep-" + UUID.randomUUID() + "@example.com");
        MatchRequest request = createMatchRequest(user.getId(), MatchRequestStatus.SEARCHING);

        withdraw(bearerToken(user.getId()), "not-my-password").andExpect(status().isForbidden());
        entityManager.flush();
        entityManager.clear();

        assertThat(matchRequestRepository.findById(request.getId()).orElseThrow().getStatus())
                .isEqualTo(MatchRequestStatus.SEARCHING);
    }

    // ---- 진행 중인 매칭 정리 -------------------------------------------------------------
    // 탈퇴자가 참가 중인 활성 매칭(PROPOSED/CONFIRMED)은 탈퇴 시점에 정리된다. 서버는 그 사람이 나오지 않을
    // 것을 확정적으로 알고, 확정된 매칭은 시간이 지나도 스스로 정리되지 않아 상대가 약속 장소에서 바람맞기 때문이다.

    // 호스트 게시글 + 러닝 조건(신청이 raw SQL로 조회함)을 만든다
    private MatchRequest createHostPost(Long hostUserId) {
        MatchRequest request = matchRequestRepository.save(new MatchRequest(
                hostUserId, OffsetDateTime.now().plusHours(48), TalkLevel.LIGHT_CHAT,
                OffsetDateTime.now().plusHours(9)
        ));
        Long courseId = jdbcTemplate.queryForObject(
                "SELECT id FROM running_course WHERE name = ? LIMIT 1", Long.class, "뚝섬 한강공원");
        jdbcTemplate.update(
                """
                INSERT INTO run_match_condition
                    (match_request_id, course_id, meeting_point, distance_min_meters, distance_max_meters, pace_min_sec, pace_max_sec)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                request.getId(), courseId, "뚝섬유원지역 3번 출구", 5000, 8000, 360, 400
        );
        return request;
    }

    private Long applyTo(Long hostPostId, Long applicantUserId) {
        Long activityMatchId = matchApplyService.apply(applicantUserId, hostPostId);
        entityManager.flush();
        return activityMatchId;
    }

    private void confirm(Long hostUserId, Long activityMatchId) {
        matchDecisionService.accept(hostUserId, activityMatchId);
        entityManager.flush();
    }

    private void withdrawSuccessfully(Long userId) throws Exception {
        entityManager.flush();
        withdraw(bearerToken(userId), PASSWORD).andExpect(status().isNoContent());
        entityManager.flush();
        entityManager.clear();
    }

    private MatchRequestStatus postStatus(Long requestId) {
        return matchRequestRepository.findById(requestId).orElseThrow().getStatus();
    }

    @Test
    @DisplayName("신청 대기(PROPOSED) 중 호스트가 탈퇴하면 매칭이 EXPIRED로 닫히고 호스트 게시글은 CANCELLED가 된다")
    void 신청_대기_중_호스트가_탈퇴하면_매칭이_만료되고_게시글이_취소된다() throws Exception {
        User host = createUser("host-p-" + UUID.randomUUID() + "@example.com");
        User applicant = createUser("app-p-" + UUID.randomUUID() + "@example.com");
        MatchRequest hostPost = createHostPost(host.getId());
        MatchRequest applicantOwnPost = createMatchRequest(applicant.getId(), MatchRequestStatus.SEARCHING);
        Long matchId = applyTo(hostPost.getId(), applicant.getId());

        withdrawSuccessfully(host.getId());

        ActivityMatch match = activityMatchRepository.findById(matchId).orElseThrow();
        assertThat(match.getStatus()).isEqualTo(ActivityMatchStatus.EXPIRED);
        assertThat(match.getClosedByUserId()).isNull();
        assertThat(matchParticipantRepository.findByActivityMatchId(matchId))
                .allSatisfy(p -> assertThat(p.getReleasedAt()).isNotNull());
        // 정리(SEARCHING 복귀) 뒤에 SEARCHING 취소가 실행돼야 한다 — 순서가 반대면 SEARCHING으로 남는다
        assertThat(postStatus(hostPost.getId())).isEqualTo(MatchRequestStatus.CANCELLED);
        // 상대(신청자)는 즉시 다른 매칭에 다시 참여할 수 있고, 본인 게시글은 모집 중으로 유지된다
        assertThat(matchParticipantRepository.findActiveActivityMatchIdByUserId(applicant.getId())).isEmpty();
        assertThat(postStatus(applicantOwnPost.getId())).isEqualTo(MatchRequestStatus.SEARCHING);
    }

    @Test
    @DisplayName("신청 대기(PROPOSED) 중 신청자가 탈퇴하면 매칭이 EXPIRED로 닫히고 호스트 게시글이 모집 중으로 복귀한다")
    void 신청_대기_중_신청자가_탈퇴하면_호스트_게시글이_모집_중으로_복귀한다() throws Exception {
        User host = createUser("host-pa-" + UUID.randomUUID() + "@example.com");
        User applicant = createUser("app-pa-" + UUID.randomUUID() + "@example.com");
        MatchRequest hostPost = createHostPost(host.getId());
        Long matchId = applyTo(hostPost.getId(), applicant.getId());
        assertThat(postStatus(hostPost.getId())).isEqualTo(MatchRequestStatus.PENDING_CONFIRMATION);

        withdrawSuccessfully(applicant.getId());

        assertThat(activityMatchRepository.findById(matchId).orElseThrow().getStatus())
                .isEqualTo(ActivityMatchStatus.EXPIRED);
        assertThat(postStatus(hostPost.getId())).isEqualTo(MatchRequestStatus.SEARCHING);
        assertThat(matchParticipantRepository.findActiveActivityMatchIdByUserId(host.getId())).isEmpty();
    }

    @Test
    @DisplayName("확정(CONFIRMED) 후 호스트가 탈퇴하면 매칭이 CANCELLED로 닫히고, 상대는 새 메시지를 못 보내지만 과거 대화는 볼 수 있다")
    void 확정_후_호스트가_탈퇴하면_매칭이_취소되고_상대_채팅은_전송만_막힌다() throws Exception {
        User host = createUser("host-c-" + UUID.randomUUID() + "@example.com");
        User applicant = createUser("app-c-" + UUID.randomUUID() + "@example.com");
        MatchRequest hostPost = createHostPost(host.getId());
        MatchRequest applicantOwnPost = createMatchRequest(applicant.getId(), MatchRequestStatus.SEARCHING);
        Long matchId = applyTo(hostPost.getId(), applicant.getId());
        confirm(host.getId(), matchId);
        chatMessageRepository.saveAndFlush(new ChatMessage(matchId, host.getId(), "내일 2번 출구에서 봬요."));
        // 노쇼 카운트가 늘지 않는지 확인하기 위한 신뢰 프로필(기본값 no_show_report_count=0)
        jdbcTemplate.update("INSERT INTO trust_profile (user_id) VALUES (?), (?)", host.getId(), applicant.getId());

        withdrawSuccessfully(host.getId());

        ActivityMatch match = activityMatchRepository.findById(matchId).orElseThrow();
        assertThat(match.getStatus()).isEqualTo(ActivityMatchStatus.CANCELLED);
        assertThat(match.getClosedByUserId()).isNull();
        assertThat(match.getClosedAt()).isNotNull();
        assertThat(match.getConfirmedAt()).isNotNull(); // 과거 대화 조회의 기준이라 유지된다
        assertThat(matchParticipantRepository.findByActivityMatchId(matchId))
                .allSatisfy(p -> assertThat(p.getReleasedAt()).isNotNull());
        assertThat(postStatus(hostPost.getId())).isEqualTo(MatchRequestStatus.CANCELLED);
        assertThat(postStatus(applicantOwnPost.getId())).isEqualTo(MatchRequestStatus.SEARCHING);

        // 노쇼가 아니라 사전 취소이므로 신고 횟수는 그대로다
        Integer noShowCount = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(no_show_report_count), 0) FROM trust_profile WHERE user_id IN (?, ?)",
                Integer.class, host.getId(), applicant.getId());
        assertThat(noShowCount).isZero();

        // 상대는 탈퇴한 사람과의 채팅에 새 메시지를 보낼 수 없다(409)
        mockMvc.perform(post("/api/matching/matches/{id}/messages", matchId)
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(applicant.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\": \"어디세요?\"}"))
                .andExpect(status().isConflict());
        // 하지만 과거 대화는 계속 볼 수 있다
        mockMvc.perform(get("/api/matching/matches/{id}/messages", matchId)
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(applicant.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].content").value("내일 2번 출구에서 봬요."));

        // 신청자 화면(내 신청 내역)에서는 직접 취소한 것이 아니므로 "취소함"이 아니라 "거절됨"으로 보인다
        assertThat(matchApplyService.getMyApplications(applicant.getId(), "REJECTED"))
                .extracting(r -> r.activityMatchId()).containsExactly(matchId);
        assertThat(matchApplyService.getMyApplications(applicant.getId(), "CANCELLED")).isEmpty();
    }

    @Test
    @DisplayName("확정(CONFIRMED) 후 신청자가 탈퇴하면 매칭이 CANCELLED로 닫히고 호스트 게시글이 다시 모집 중이 된다")
    void 확정_후_신청자가_탈퇴하면_호스트_게시글이_모집_중으로_복귀한다() throws Exception {
        User host = createUser("host-ca-" + UUID.randomUUID() + "@example.com");
        User applicant = createUser("app-ca-" + UUID.randomUUID() + "@example.com");
        MatchRequest hostPost = createHostPost(host.getId());
        Long matchId = applyTo(hostPost.getId(), applicant.getId());
        confirm(host.getId(), matchId);
        assertThat(postStatus(hostPost.getId())).isEqualTo(MatchRequestStatus.MATCHED);

        withdrawSuccessfully(applicant.getId());

        assertThat(activityMatchRepository.findById(matchId).orElseThrow().getStatus())
                .isEqualTo(ActivityMatchStatus.CANCELLED);
        assertThat(postStatus(hostPost.getId())).isEqualTo(MatchRequestStatus.SEARCHING);
        // 호스트가 즉시 다시 모집할 수 있다 — 다른 사람이 같은 글에 신청할 수 있다
        User another = createUser("app-ca2-" + UUID.randomUUID() + "@example.com");
        assertThat(matchApplyService.apply(another.getId(), hostPost.getId())).isNotNull();
    }

    @Test
    @DisplayName("비밀번호가 틀려 탈퇴가 거부되면 진행 중인 매칭도 그대로 유지된다")
    void 탈퇴가_거부되면_진행_중인_매칭도_유지된다() throws Exception {
        User host = createUser("host-r-" + UUID.randomUUID() + "@example.com");
        User applicant = createUser("app-r-" + UUID.randomUUID() + "@example.com");
        MatchRequest hostPost = createHostPost(host.getId());
        Long matchId = applyTo(hostPost.getId(), applicant.getId());
        confirm(host.getId(), matchId);

        withdraw(bearerToken(host.getId()), "not-my-password").andExpect(status().isForbidden());
        entityManager.flush();
        entityManager.clear();

        assertThat(activityMatchRepository.findById(matchId).orElseThrow().getStatus())
                .isEqualTo(ActivityMatchStatus.CONFIRMED);
        assertThat(matchParticipantRepository.findByActivityMatchId(matchId))
                .allSatisfy(p -> assertThat(p.getReleasedAt()).isNull());
        assertThat(postStatus(hostPost.getId())).isEqualTo(MatchRequestStatus.MATCHED);
    }

    // ---- 활동 시각 경계(시작 전 / 시작 후·종료 전 / 종료 후) ------------------------------------------
    // 종료 시각은 지났지만 1분 주기 스케줄러(endOverdueActivities())가 아직 처리하지 않은 CONFIRMED 매칭은
    // 이미 끝난 활동이다. 취소로 바꾸거나 지난 일정의 게시글을 다시 모집 중으로 되돌리면 안 된다.
    // chk_activity_match_time(created_at < decision_expires_at < scheduled_at < scheduled_end_at)을 지키도록
    // 생성·응답 기한은 항상 시작보다 충분히 앞선 과거(5시간·4시간 전)로 두고 시작·종료 시각만 옮긴다.
    private void moveActivityTimes(Long activityMatchId, String scheduledAt, String scheduledEndAt) {
        jdbcTemplate.update(
                "UPDATE activity_match SET created_at = CURRENT_TIMESTAMP - INTERVAL '5 hours', "
                        + "decision_expires_at = CURRENT_TIMESTAMP - INTERVAL '4 hours', "
                        + "scheduled_at = CURRENT_TIMESTAMP + INTERVAL '" + scheduledAt + "', "
                        + "scheduled_end_at = CURRENT_TIMESTAMP + INTERVAL '" + scheduledEndAt + "' WHERE id = ?",
                activityMatchId
        );
        entityManager.clear();
    }

    @Test
    @DisplayName("활동 종료 시각이 지난 확정 매칭은 탈퇴 시 CANCELLED가 아니라 ENDED로, 게시글은 CLOSED로 처리한다")
    void 종료_시각이_지난_확정_매칭은_ENDED로_정리된다() throws Exception {
        User host = createUser("host-e-" + UUID.randomUUID() + "@example.com");
        User applicant = createUser("app-e-" + UUID.randomUUID() + "@example.com");
        MatchRequest hostPost = createHostPost(host.getId());
        Long matchId = applyTo(hostPost.getId(), applicant.getId());
        confirm(host.getId(), matchId);
        moveActivityTimes(matchId, "-2 hours", "-1 hour"); // 종료 시각이 1시간 전

        withdrawSuccessfully(applicant.getId());

        ActivityMatch match = activityMatchRepository.findById(matchId).orElseThrow();
        assertThat(match.getStatus()).isEqualTo(ActivityMatchStatus.ENDED);
        assertThat(match.getClosedByUserId()).isNull();
        assertThat(matchParticipantRepository.findByActivityMatchId(matchId))
                .allSatisfy(p -> assertThat(p.getReleasedAt()).isNotNull());
        // 정상 종료된 활동이라 지난 일정의 게시글이 다시 모집 중이 되면 안 된다
        assertThat(postStatus(hostPost.getId())).isEqualTo(MatchRequestStatus.CLOSED);
    }

    // 활동이 이미 시작된 게시글을 SEARCHING으로 되돌리면 모집 탭에는 보이지만 apply()의 응답 기한 계산
    // (시작 1시간 전까지)에 걸려 신청할 수 없는 글이 된다. 그래서 시작 후에는 CLOSED로 둔다.
    @Test
    @DisplayName("활동이 시작된 뒤 종료 전인 확정 매칭은 탈퇴 시 CANCELLED로, 게시글은 SEARCHING이 아니라 CLOSED로 처리한다")
    void 시작_후_종료_전_확정_매칭은_CANCELLED와_게시글_CLOSED로_정리된다() throws Exception {
        User host = createUser("host-i-" + UUID.randomUUID() + "@example.com");
        User applicant = createUser("app-i-" + UUID.randomUUID() + "@example.com");
        MatchRequest hostPost = createHostPost(host.getId());
        Long matchId = applyTo(hostPost.getId(), applicant.getId());
        confirm(host.getId(), matchId);
        moveActivityTimes(matchId, "-1 hour", "1 hour"); // 시작했지만 아직 종료 시각 전

        withdrawSuccessfully(applicant.getId());

        ActivityMatch match = activityMatchRepository.findById(matchId).orElseThrow();
        assertThat(match.getStatus()).isEqualTo(ActivityMatchStatus.CANCELLED); // 아직 끝난 활동이 아니라 취소
        assertThat(match.getClosedByUserId()).isNull();
        assertThat(matchParticipantRepository.findByActivityMatchId(matchId))
                .allSatisfy(p -> assertThat(p.getReleasedAt()).isNotNull());
        assertThat(postStatus(hostPost.getId())).isEqualTo(MatchRequestStatus.CLOSED);
    }

    @Test
    @DisplayName("활동 시작 전인 확정 매칭은 탈퇴 시 CANCELLED로, 게시글은 SEARCHING으로 처리한다")
    void 시작_전_확정_매칭은_CANCELLED와_게시글_SEARCHING으로_정리된다() throws Exception {
        User host = createUser("host-b-" + UUID.randomUUID() + "@example.com");
        User applicant = createUser("app-b-" + UUID.randomUUID() + "@example.com");
        MatchRequest hostPost = createHostPost(host.getId());
        Long matchId = applyTo(hostPost.getId(), applicant.getId());
        confirm(host.getId(), matchId);
        moveActivityTimes(matchId, "1 hour", "3 hours"); // 아직 시작 전(경계에 가깝게 1시간 뒤 시작)

        withdrawSuccessfully(applicant.getId());

        assertThat(activityMatchRepository.findById(matchId).orElseThrow().getStatus())
                .isEqualTo(ActivityMatchStatus.CANCELLED);
        assertThat(postStatus(hostPost.getId())).isEqualTo(MatchRequestStatus.SEARCHING);
    }

    // PROPOSED도 같은 규칙이다. 스케줄러가 멈춰 응답 기한이 지난 채 활동 시각까지 지나간 신청 대기 매칭이
    // 탈퇴 시 정리되더라도, 이미 시작된 활동의 게시글을 모집 중으로 되돌리지 않는다.
    @Test
    @DisplayName("활동이 이미 시작된 신청 대기 매칭은 탈퇴 시 EXPIRED로, 게시글은 SEARCHING이 아니라 CLOSED로 처리한다")
    void 시작_후_신청_대기_매칭은_EXPIRED와_게시글_CLOSED로_정리된다() throws Exception {
        User host = createUser("host-ps-" + UUID.randomUUID() + "@example.com");
        User applicant = createUser("app-ps-" + UUID.randomUUID() + "@example.com");
        MatchRequest hostPost = createHostPost(host.getId());
        Long matchId = applyTo(hostPost.getId(), applicant.getId());
        moveActivityTimes(matchId, "-1 hour", "1 hour");

        withdrawSuccessfully(applicant.getId());

        assertThat(activityMatchRepository.findById(matchId).orElseThrow().getStatus())
                .isEqualTo(ActivityMatchStatus.EXPIRED);
        assertThat(postStatus(hostPost.getId())).isEqualTo(MatchRequestStatus.CLOSED);
    }

    // ---- 탈퇴 비밀번호 72바이트 상한(로그인·회원가입과 동일) ---------------------------------------
    // BCrypt가 처리하는 72바이트를 넘는 입력은 비교 전에 400으로 차단한다. 검증이 서비스보다 먼저 동작하므로
    // 403(비밀번호 불일치)이 아니라 400이 나오고 계정은 변경되지 않는다.
    @Test
    @DisplayName("비밀번호가 UTF-8 72바이트를 넘으면 비밀번호 비교 전에 400으로 거부한다")
    void 비밀번호가_72바이트를_넘으면_400을_반환한다() throws Exception {
        User user = createUser("bytes-" + UUID.randomUUID() + "@example.com");
        String token = bearerToken(user.getId());

        // ASCII 73자 = 73바이트
        withdraw(token, "a".repeat(73))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("비밀번호가 허용 길이를 초과했습니다. 더 짧게 작성해주세요."));
        // 한글 25자 = 75바이트 — 글자 수가 아니라 바이트 기준이다
        withdraw(token, "가".repeat(25)).andExpect(status().isBadRequest());

        entityManager.flush();
        entityManager.clear();
        assertThat(userRepository.findById(user.getId()).orElseThrow().getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("비밀번호가 정확히 72바이트면 검증을 통과해 비밀번호 비교까지 진행된다")
    void 비밀번호가_정확히_72바이트면_검증을_통과한다() throws Exception {
        User user = createUser("bytes72-" + UUID.randomUUID() + "@example.com");

        // 검증은 통과하지만 본인 비밀번호가 아니므로 400이 아니라 403(불일치)이 나온다
        withdraw(bearerToken(user.getId()), "a".repeat(72)).andExpect(status().isForbidden());
        withdraw(bearerToken(user.getId()), "가".repeat(24)).andExpect(status().isForbidden()); // 24자 = 72바이트
    }
}
