package com.team4.hanbbyeom.matching;

import com.team4.hanbbyeom.global.security.jwt.JwtTokenProvider;
import com.team4.hanbbyeom.matching.domain.MatchRequest;
import com.team4.hanbbyeom.matching.domain.TalkLevel;
import com.team4.hanbbyeom.matching.dto.MatchRequestCreateRequest;
import com.team4.hanbbyeom.matching.repository.ActivityMatchRepository;
import com.team4.hanbbyeom.matching.repository.MatchParticipantRepository;
import com.team4.hanbbyeom.matching.repository.MatchRequestRepository;
import com.team4.hanbbyeom.matching.service.MatchDecisionService;
import com.team4.hanbbyeom.matching.service.MatchRequestCommandService;
import com.team4.hanbbyeom.trust.repository.TrustProfileRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 모집 기한(search_expires_at)이 지난 모집 중(SEARCHING) 게시글의 자동 만료(EXPIRED)를 실제 DB로 검증한다(이슈 #107).
// 만료 처리는 일괄 UPDATE라 영속성 컨텍스트를 거치지 않으므로, 상태는 JDBC로 직접 읽는다.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ExpireOverdueRequestsIntegrationTest {

    @PersistenceContext private EntityManager entityManager;
    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private MatchRequestRepository matchRequestRepository;
    @Autowired private ActivityMatchRepository activityMatchRepository;
    @Autowired private MatchParticipantRepository matchParticipantRepository;
    @Autowired private MatchDecisionService matchDecisionService;
    @Autowired private TrustProfileRepository trustProfileRepository;
    @Autowired private MatchRequestCommandService matchRequestCommandService;

    private Long createUser(String label) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, nickname, email_verified_at) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class,
                label + "-" + UUID.randomUUID() + "@example.com", "dummy-hash", label, OffsetDateTime.now()
        );
    }

    // JDBC로 바꾼 값은 영속성 컨텍스트에 반영되지 않는다. 테스트는 한 트랜잭션이라 save()로 만든 엔티티가 낡은 상태로 남고,
    // apply() 같은 JPA 조회가 그걸 읽어 실제 DB와 다르게 동작한다(운영에서는 스케줄러와 요청의 컨텍스트가 서로 달라 해당 없음).
    private void syncFromDb() {
        entityManager.flush();
        entityManager.clear();
    }

    // 만료 처리를 실행하고, 이후 JPA로 읽는 코드가 DB의 최신 상태를 보도록 컨텍스트를 비운다
    private int expireNow() {
        int expired = matchDecisionService.expireOverdueRequests();
        syncFromDb();
        return expired;
    }

    private String bearerToken(Long userId) {
        return "Bearer " + jwtTokenProvider.createAccessToken(userId);
    }

    // 모집글 + 러닝 조건. 시각은 기본값(활동 48시간 뒤)이며 필요하면 setPostTimes()로 바꾼다.
    private Long createPost(Long hostId) {
        MatchRequest post = matchRequestRepository.save(new MatchRequest(
                hostId, OffsetDateTime.now().plusHours(48), TalkLevel.LIGHT_CHAT, OffsetDateTime.now().plusHours(9)));
        Long courseId = jdbcTemplate.queryForObject(
                "SELECT id FROM running_course WHERE name = ? LIMIT 1", Long.class, "뚝섬 한강공원");
        jdbcTemplate.update(
                """
                INSERT INTO run_match_condition
                    (match_request_id, course_id, meeting_point, distance_min_meters, distance_max_meters, pace_min_sec, pace_max_sec)
                VALUES (?, ?, '뚝섬유원지역 3번 출구', 5000, 8000, 360, 400)
                """,
                post.getId(), courseId
        );
        return post.getId();
    }

    // chk_match_request_time(created_at < search_expires_at < scheduled_at)을 지키면서 시각을 옮긴다(now 기준 상대값).
    private void setPostTimes(Long postId, String created, String searchExpires, String scheduled) {
        jdbcTemplate.update(
                "UPDATE match_request SET created_at = now() + INTERVAL '" + created + "', "
                        + "search_expires_at = now() + INTERVAL '" + searchExpires + "', "
                        + "scheduled_at = now() + INTERVAL '" + scheduled + "' WHERE id = ?",
                postId
        );
        syncFromDb();
    }

    // 기한이 지난 상태: 모집 기한이 2일 전, 활동 시각이 1일 전
    private void makeOverdue(Long postId) {
        setPostTimes(postId, "-3 days", "-2 days", "-1 day");
    }

    private void setStatus(Long postId, String status) {
        jdbcTemplate.update("UPDATE match_request SET status = ? WHERE id = ?", status, postId);
        syncFromDb();
    }

    private String statusOf(Long postId) {
        return jdbcTemplate.queryForObject("SELECT status FROM match_request WHERE id = ?", String.class, postId);
    }

    @Test
    @DisplayName("모집 기한이 지난 SEARCHING 게시글만 EXPIRED가 되고, 다른 게시글은 그대로다")
    void 기한이_지난_모집_중_게시글만_만료된다() {
        Long overdueSearching = createPost(createUser("기한지남"));
        makeOverdue(overdueSearching);

        Long aliveSearching = createPost(createUser("기한남음"));
        setPostTimes(aliveSearching, "0 seconds", "1 hour", "2 hours"); // 아직 모집 기한 전

        // 기한이 지났더라도 SEARCHING이 아니면 이 작업의 대상이 아니다 — 각자 다른 처리(응답 기한·종료·취소)를 따른다
        Long pending = createPost(createUser("신청대기"));
        makeOverdue(pending);
        setStatus(pending, "PENDING_CONFIRMATION");
        Long matched = createPost(createUser("확정"));
        makeOverdue(matched);
        setStatus(matched, "MATCHED");
        Long cancelled = createPost(createUser("취소"));
        makeOverdue(cancelled);
        setStatus(cancelled, "CANCELLED");
        Long closed = createPost(createUser("종료"));
        makeOverdue(closed);
        setStatus(closed, "CLOSED");

        int expired = expireNow();

        assertThat(expired).isEqualTo(1);
        assertThat(statusOf(overdueSearching)).isEqualTo("EXPIRED");
        assertThat(statusOf(aliveSearching)).isEqualTo("SEARCHING");
        assertThat(statusOf(pending)).isEqualTo("PENDING_CONFIRMATION");
        assertThat(statusOf(matched)).isEqualTo("MATCHED");
        assertThat(statusOf(cancelled)).isEqualTo("CANCELLED");
        assertThat(statusOf(closed)).isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("여러 번 실행해도 결과가 같다 (한 번 만료된 글은 다시 세지 않는다)")
    void 여러_번_실행해도_결과가_같다() {
        Long first = createPost(createUser("첫째"));
        Long second = createPost(createUser("둘째"));
        makeOverdue(first);
        makeOverdue(second);

        assertThat(expireNow()).isEqualTo(2);
        assertThat(expireNow()).isZero();
        assertThat(expireNow()).isZero();

        assertThat(statusOf(first)).isEqualTo("EXPIRED");
        assertThat(statusOf(second)).isEqualTo("EXPIRED");
    }

    // 활성 게시글은 사용자당 하나뿐(uq_match_request_active_user)이라, 기한이 지난 글이 SEARCHING으로 남아 있으면
    // 호스트는 새 글을 올릴 수 없었다. EXPIRED는 그 유니크 인덱스 대상이 아니라 호스트가 풀린다.
    @Test
    @DisplayName("만료되면 호스트가 새 모집글을 등록할 수 있다")
    void 만료되면_호스트가_새_모집글을_등록할_수_있다() {
        Long host = createUser("호스트");
        Long stale = createPost(host);
        makeOverdue(stale);

        expireNow();

        Long courseId = jdbcTemplate.queryForObject(
                "SELECT id FROM running_course WHERE name = ? LIMIT 1", Long.class, "뚝섬 한강공원");
        Long newPostId = matchRequestCommandService.create(host, new MatchRequestCreateRequest(
                courseId, "새 장소", 5000, 8000, 360, 400, OffsetDateTime.now().plusHours(48), "LIGHT_CHAT"));

        assertThat(newPostId).isNotEqualTo(stale);
        assertThat(statusOf(newPostId)).isEqualTo("SEARCHING");
        assertThat(statusOf(stale)).isEqualTo("EXPIRED");
    }

    @Test
    @DisplayName("만료된 글은 내 활성 모집글에서 사라지고 내 모집글 목록에는 EXPIRED로 나온다")
    void 만료된_글은_내_활성_모집글에서_사라진다() throws Exception {
        Long host = createUser("호스트");
        Long stale = createPost(host);
        makeOverdue(stale);

        // 만료 전에는 지난 글이 그대로 "내 활성 모집글"로 조회된다(문제 상황)
        mockMvc.perform(get("/api/matching/requests/me").header(HttpHeaders.AUTHORIZATION, bearerToken(host)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(stale));

        expireNow();

        mockMvc.perform(get("/api/matching/requests/me").header(HttpHeaders.AUTHORIZATION, bearerToken(host)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/matching/requests").header(HttpHeaders.AUTHORIZATION, bearerToken(host)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + stale + ")].status").value(hasItem("EXPIRED")));
    }

    @Test
    @DisplayName("만료된 글에 신청하면 마감된 글로 409를 반환하고 매칭이 만들어지지 않는다")
    void 만료된_글에_신청하면_409다() throws Exception {
        Long host = createUser("호스트");
        Long applicant = createUser("신청자");
        Long stale = createPost(host);
        makeOverdue(stale);
        expireNow();

        mockMvc.perform(post("/api/matching/board/{id}/apply", stale)
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(applicant)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("이미 마감되었거나 신청이 진행 중인 모집글이에요."));

        assertThat(activityMatchRepository.count()).isZero();
        assertThat(matchParticipantRepository.count()).isZero();
        assertThat(statusOf(stale)).isEqualTo("EXPIRED");
    }

    // 경계는 포함(search_expires_at <= now)이다. apply()가 신청을 거부하는 시점(now >= scheduledAt - 1시간 = search_expires_at)과
    // 같아야 "신청할 수 없는 글은 마감된 글"이 성립한다. 시각을 정확히 맞추려고 고정 시계를 넣은 서비스를 직접 만든다.
    @Test
    @DisplayName("경계: 모집 기한과 정확히 같은 시각도 만료되고, 1초 남은 글은 만료되지 않는다")
    void 경계_시각은_포함한다() {
        OffsetDateTime boundary = OffsetDateTime.of(2026, 9, 1, 10, 0, 0, 0, ZoneOffset.UTC);
        MatchDecisionService atBoundary = new MatchDecisionService(
                matchRequestRepository, activityMatchRepository, matchParticipantRepository, jdbcTemplate,
                trustProfileRepository, Clock.fixed(boundary.toInstant(), ZoneOffset.UTC));

        Long exactly = createPost(createUser("정확히"));
        Long oneSecondBefore = createPost(createUser("일초전에만료"));
        Long oneSecondLeft = createPost(createUser("일초남음"));
        setPostExpiry(exactly, boundary);
        setPostExpiry(oneSecondBefore, boundary.minusSeconds(1));
        setPostExpiry(oneSecondLeft, boundary.plusSeconds(1));

        int expired = atBoundary.expireOverdueRequests();

        assertThat(expired).isEqualTo(2);
        assertThat(statusOf(exactly)).isEqualTo("EXPIRED");
        assertThat(statusOf(oneSecondBefore)).isEqualTo("EXPIRED");
        assertThat(statusOf(oneSecondLeft)).isEqualTo("SEARCHING");
    }

    // 절대 시각으로 모집 기한을 지정한다(created_at < search_expires_at < scheduled_at 유지)
    private void setPostExpiry(Long postId, OffsetDateTime searchExpiresAt) {
        jdbcTemplate.update(
                "UPDATE match_request SET created_at = ?, search_expires_at = ?, scheduled_at = ? WHERE id = ?",
                searchExpiresAt.minusDays(1), searchExpiresAt, searchExpiresAt.plusHours(1), postId
        );
        syncFromDb();
    }
}
