package com.team4.hanbbyeom.matching.service;

import com.team4.hanbbyeom.matching.domain.AcceptStatus;
import com.team4.hanbbyeom.matching.domain.ActivityMatch;
import com.team4.hanbbyeom.matching.domain.MatchParticipant;
import com.team4.hanbbyeom.matching.domain.MatchRequest;
import com.team4.hanbbyeom.matching.domain.TalkLevel;
import com.team4.hanbbyeom.matching.dto.MatchRequestResponse;
import com.team4.hanbbyeom.matching.dto.MatchBoardItemResponse;
import com.team4.hanbbyeom.matching.dto.MyPostResponse;
import com.team4.hanbbyeom.matching.dto.PendingApplicationResponse;
import com.team4.hanbbyeom.matching.exception.MatchRequestNotFoundException;
import com.team4.hanbbyeom.matching.exception.PendingApplicationNotFoundException;
import com.team4.hanbbyeom.matching.repository.ActivityMatchRepository;
import com.team4.hanbbyeom.matching.repository.MatchParticipantRepository;
import com.team4.hanbbyeom.matching.repository.MatchRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class MatchRequestBoardServiceTest {

    @Autowired
    private MatchRequestBoardService matchRequestBoardService;
    @Autowired
    private MatchRequestRepository matchRequestRepository;
    @Autowired
    private MatchApplyService matchApplyService;
    @Autowired
    private ActivityMatchRepository activityMatchRepository;
    @Autowired
    private MatchParticipantRepository matchParticipantRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long testUserId;
    private Long testCourseId;
    private Long testMatchRequestId;

    @BeforeEach
    void setUp() {
        // users: chk_users_account_lifecycle 제약 — 활성 계정이면 email/password_hash/nickname/email_verified_at 전부 NOT NULL이어야 함
        testUserId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, nickname, email_verified_at) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class,
                "test-user-" + System.nanoTime() + "@example.com", "dummy-hash", "테스트유저", OffsetDateTime.now()
        );

        // running_course: V5에서 이미 5개 코스가 시딩돼 있으니 새로 만들지 않고 그중 하나를 가져다 씀
        testCourseId = jdbcTemplate.queryForObject(
                "SELECT id FROM running_course WHERE name = ? LIMIT 1",
                Long.class, "뚝섬 한강공원"
        );

        // scheduledAt은 48시간 뒤로 잡는다 — MatchApplyService.apply()의 24시간 응답 대기
        // 기한(DECISION_WINDOW_HOURS)보다 넉넉히 뒤여야 getPendingApplication() 테스트에서
        // apply()가 "활동 시작 시각이 너무 임박함" 예외 없이 성공한다.
        MatchRequest matchRequest = new MatchRequest(
                testUserId, OffsetDateTime.now().plusHours(48), TalkLevel.LIGHT_CHAT,
                OffsetDateTime.now().plusHours(4)
        );
        testMatchRequestId = matchRequestRepository.save(matchRequest).getId();

        jdbcTemplate.update(
                """
                INSERT INTO run_match_condition
                    (match_request_id, course_id, meeting_point, distance_min_meters, distance_max_meters, pace_min_sec, pace_max_sec)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                testMatchRequestId, testCourseId, "뚝섬유원지역 3번 출구", 5000, 8000, 360, 400
        );

        jdbcTemplate.update(
                "INSERT INTO trust_profile (user_id, average_rating, completed_activity_count, no_show_report_count) VALUES (?, ?, ?, ?)",
                testUserId, 4.5, 12, 2
        );
    }

    private Long createUser(String label) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, nickname, email_verified_at) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class,
                label + "-" + System.nanoTime() + "@example.com", "dummy-hash", label, OffsetDateTime.now()
        );
    }

    // testUserId(모집글 작성자)가 후기를 "받는" 상황을 만든다 — 새 리뷰어와 끝난 매칭을 하나 만들고,
    // 그 매칭에서 리뷰어가 testUserId에게 후기를 남긴 것으로 activity_review를 직접 INSERT한다.
    // activity_review는 (activity_match_id, reviewer/reviewee_user_id)가 match_participant를 참조하는
    // 복합 FK가 있어서 참가자 등록이 먼저 필요하다. testUserId 쪽 참가 행은 setUp()의 게시글
    // (testMatchRequestId)을 그대로 쓴다 — match_participant가 match_request(id, user_id)를 참조하므로
    // 본인 소유 게시글이어야 한다.
    private void giveReviewToTestUser(String comment) {
        Long reviewerId = createUser("리뷰어");
        Long reviewerRequestId = matchRequestRepository.save(
                new MatchRequest(reviewerId, OffsetDateTime.now().plusHours(48), TalkLevel.SILENT, OffsetDateTime.now().plusHours(4))
        ).getId();

        OffsetDateTime base = OffsetDateTime.now().minusHours(2);
        ActivityMatch activityMatch = new ActivityMatch(
                base.plusMinutes(20), base.plusMinutes(30), TalkLevel.SILENT,
                "뚝섬 한강공원", "뚝섬 한강공원 코스", 5000, 12000,
                "뚝섬유원지역 3번 출구", 360, 400,
                base.plusMinutes(10), base
        );
        activityMatch.confirm("123456");
        activityMatch.end();
        Long activityMatchId = activityMatchRepository.save(activityMatch).getId();

        matchParticipantRepository.save(new MatchParticipant(activityMatchId, testMatchRequestId, testUserId, "A", AcceptStatus.ACCEPTED));
        matchParticipantRepository.save(new MatchParticipant(activityMatchId, reviewerRequestId, reviewerId, "B", AcceptStatus.ACCEPTED));

        jdbcTemplate.update("""
                INSERT INTO activity_review
                    (activity_match_id, reviewer_user_id, reviewee_user_id, rating, perceived_talk_level, comment)
                VALUES (?, ?, ?, 5, 'SILENT', ?)
                """, activityMatchId, reviewerId, testUserId, comment);
    }

    @Test
    void 코스_필터로_조회하면_일치하는_게시글만_반환되고_작성자_신뢰정보가_join된다() {
        List<MatchBoardItemResponse> result = matchRequestBoardService.getBoard(
                "뚝섬 한강공원", null, null, null, null, null, null, testUserId + 1 // 본인 제외되게 다른 id로 조회
        );

        assertThat(result).hasSize(1);
        MatchBoardItemResponse item = result.get(0);
        assertThat(item.courseName()).isEqualTo("뚝섬 한강공원");
        assertThat(item.author().rating()).isEqualTo(4.5);
        assertThat(item.author().completedCount()).isEqualTo(12);
    }

    @Test
    void 코스_필터가_일치하지_않으면_조회되지_않는다() {
        List<MatchBoardItemResponse> result = matchRequestBoardService.getBoard(
                "여의도 한강공원", null, null, null, null, null, null, testUserId + 1
        );

        assertThat(result).isEmpty();
    }

    @Test
    void 거리_범위가_겹치지_않으면_조회되지_않는다() {
        List<MatchBoardItemResponse> result = matchRequestBoardService.getBoard(
                null, null, 9000, 10000, null, null, null, testUserId + 1 // 테스트 데이터 범위(5000~8000)와 안 겹침
        );

        assertThat(result).isEmpty();
    }

    @Test
    void 페이스_범위가_겹치지_않으면_조회되지_않는다() {
        List<MatchBoardItemResponse> result = matchRequestBoardService.getBoard(
                null, null, null, null, 450, 500, null, testUserId + 1 // 테스트 데이터 범위(360~400)와 안 겹침
        );

        assertThat(result).isEmpty();
    }

    @Test
    void 본인이_작성한_게시글은_목록에서_제외된다() {
        List<MatchBoardItemResponse> result = matchRequestBoardService.getBoard(
                null, null, null, null, null, null, null, testUserId // 작성자 본인으로 조회
        );

        assertThat(result).isEmpty();
    }

    @Test
    void 상세_조회시_코스명과_거리_페이스가_정확히_매핑된다() {
        MatchRequestResponse response = matchRequestBoardService.getDetail(testMatchRequestId, testUserId);

        assertThat(response.courseName()).isEqualTo("뚝섬 한강공원");
        assertThat(response.distanceMinMeters()).isEqualTo(5000);
        assertThat(response.distanceMaxMeters()).isEqualTo(8000);
        assertThat(response.isOwner()).isTrue();
    }

    // 이슈 #70 완료 기준: 목록(getBoard)과 상세(getDetail)가 작성자 신뢰 정보를 "같은 값"으로 내려줘야 한다.
    // 상세는 이전에 AuthorSummary(nickname, null, null)로 하드코딩되어 있어서 이 테스트가 없었으면
    // 그 버그가 그대로 남았을 것이다. 후기가 없는 상태에서는 latestReview가 양쪽 모두 null이어야 한다.
    @Test
    void 목록과_상세가_같은_작성자_신뢰정보를_내려준다() {
        MatchBoardItemResponse.AuthorSummary fromBoard = matchRequestBoardService.getBoard(
                null, null, null, null, null, null, null, testUserId + 1
        ).get(0).author();
        MatchBoardItemResponse.AuthorSummary fromDetail =
                matchRequestBoardService.getDetail(testMatchRequestId, testUserId).author();

        assertThat(fromDetail.rating()).isEqualTo(4.5);
        assertThat(fromDetail.completedCount()).isEqualTo(12);
        assertThat(fromDetail.noShowCount()).isEqualTo(2);
        assertThat(fromDetail.latestReview()).isNull();

        assertThat(fromBoard.rating()).isEqualTo(fromDetail.rating());
        assertThat(fromBoard.completedCount()).isEqualTo(fromDetail.completedCount());
        assertThat(fromBoard.noShowCount()).isEqualTo(fromDetail.noShowCount());
        assertThat(fromBoard.latestReview()).isEqualTo(fromDetail.latestReview());
    }

    @Test
    void 최근_후기가_있으면_목록과_상세에_같은_후기가_채워진다() {
        giveReviewToTestUser("페이스 잘 맞춰주셨어요");

        MatchBoardItemResponse.AuthorSummary fromBoard = matchRequestBoardService.getBoard(
                null, null, null, null, null, null, null, testUserId + 1
        ).get(0).author();
        MatchBoardItemResponse.AuthorSummary fromDetail =
                matchRequestBoardService.getDetail(testMatchRequestId, testUserId).author();

        assertThat(fromDetail.latestReview()).isNotNull();
        assertThat(fromDetail.latestReview().comment()).isEqualTo("페이스 잘 맞춰주셨어요");
        assertThat(fromDetail.latestReview().createdAt()).isNotNull();
        assertThat(fromBoard.latestReview()).isEqualTo(fromDetail.latestReview());
    }

    // 별점만 남기고 한 줄 후기(comment)는 안 쓴 후기도 "최근 후기"로 잡혀야 한다.
    // 후기 존재 여부를 comment==null로 판단하면 이 케이스가 통째로 사라진다 — createdAt으로 판단하는지 확인.
    @Test
    void 별점만_남긴_후기도_최근_후기로_잡힌다() {
        giveReviewToTestUser(null);

        MatchBoardItemResponse.AuthorSummary fromDetail =
                matchRequestBoardService.getDetail(testMatchRequestId, testUserId).author();

        assertThat(fromDetail.latestReview()).isNotNull();
        assertThat(fromDetail.latestReview().comment()).isNull();
        assertThat(fromDetail.latestReview().createdAt()).isNotNull();
    }

    @Test
    void 대기중인_신청이_있으면_activityMatchId를_반환한다() {
        Long applicantUserId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, nickname, email_verified_at) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class,
                "applicant-" + System.nanoTime() + "@example.com", "dummy-hash", "신청자", OffsetDateTime.now()
        );
        Long activityMatchId = matchApplyService.apply(applicantUserId, testMatchRequestId);

        PendingApplicationResponse response =
                matchRequestBoardService.getPendingApplication(testUserId, testMatchRequestId);

        assertThat(response.activityMatchId()).isEqualTo(activityMatchId);
        assertThat(response.decisionExpiresAt()).isNotNull();
    }

    @Test
    void 본인_게시글이_아니면_조회_시도시_예외가_발생한다() {
        assertThatThrownBy(() -> matchRequestBoardService.getPendingApplication(testUserId + 1, testMatchRequestId))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void 대기중인_신청이_없으면_예외가_발생한다() {
        assertThatThrownBy(() -> matchRequestBoardService.getPendingApplication(testUserId, testMatchRequestId))
                .isInstanceOf(PendingApplicationNotFoundException.class);
    }

    @Test
    void 내_모집글_목록을_조회하면_상태와_무관하게_전부_반환된다() {
        // uq_match_request_active_user는 활성 상태(SEARCHING/PENDING_CONFIRMATION/MATCHED)인
        // 게시글을 유저당 1개만 허용한다. testUserId는 이미 SEARCHING 게시글을 갖고 있으므로,
        // 두 번째 게시글을 엔티티(기본 상태=SEARCHING)로 만들면 곧바로 제약을 위반한다. 그래서
        // raw SQL로 처음부터 비활성 상태(CANCELLED)로 INSERT한다.
        Long secondRequestId = jdbcTemplate.queryForObject(
                """
                INSERT INTO match_request (user_id, scheduled_at, talk_level, status, search_expires_at)
                VALUES (?, ?, ?, 'CANCELLED', ?)
                RETURNING id
                """,
                Long.class,
                testUserId, OffsetDateTime.now().plusHours(72), "SILENT", OffsetDateTime.now().plusHours(24)
        );
        jdbcTemplate.update(
                """
                INSERT INTO run_match_condition
                    (match_request_id, course_id, meeting_point, distance_min_meters, distance_max_meters, pace_min_sec, pace_max_sec)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                secondRequestId, testCourseId, "여의도 한강공원", 3000, 6000, 300, 350
        );

        List<MyPostResponse> myPosts = matchRequestBoardService.getMyPosts(testUserId);

        assertThat(myPosts)
                .extracting(MyPostResponse::id, MyPostResponse::status)
                .containsExactlyInAnyOrder(
                        tuple(testMatchRequestId, "SEARCHING"),
                        tuple(secondRequestId, "CANCELLED")
                );
    }

    @Test
    void 다른_사람이_등록한_모집글은_내_목록에_나오지_않는다() {
        Long otherUserId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, nickname, email_verified_at) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class,
                "other-" + System.nanoTime() + "@example.com", "dummy-hash", "다른사람", OffsetDateTime.now()
        );

        List<MyPostResponse> myPosts = matchRequestBoardService.getMyPosts(otherUserId);

        assertThat(myPosts).isEmpty();
    }

    @Test
    void 내_활성_모집글을_조회하면_해당_게시글이_반환된다() {
        MatchRequestResponse response = matchRequestBoardService.getMyActiveRequest(testUserId);

        assertThat(response.id()).isEqualTo(testMatchRequestId);
        assertThat(response.isOwner()).isTrue();
    }

    @Test
    void 활성_모집글이_없으면_예외가_발생한다() {
        Long noRequestUserId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, nickname, email_verified_at) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class,
                "no-request-" + System.nanoTime() + "@example.com", "dummy-hash", "글없음", OffsetDateTime.now()
        );

        assertThatThrownBy(() -> matchRequestBoardService.getMyActiveRequest(noRequestUserId))
                .isInstanceOf(MatchRequestNotFoundException.class);
    }
}