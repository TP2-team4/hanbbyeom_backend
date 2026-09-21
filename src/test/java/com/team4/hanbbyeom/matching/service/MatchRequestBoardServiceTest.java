package com.team4.hanbbyeom.matching.service;

import com.team4.hanbbyeom.global.exception.InvalidRequestValueException;
import com.team4.hanbbyeom.matching.domain.AcceptStatus;
import com.team4.hanbbyeom.matching.domain.ActivityMatch;
import com.team4.hanbbyeom.matching.domain.MatchParticipant;
import com.team4.hanbbyeom.matching.domain.MatchRequest;
import com.team4.hanbbyeom.matching.domain.TalkLevel;
import com.team4.hanbbyeom.matching.dto.MatchRequestResponse;
import com.team4.hanbbyeom.matching.dto.BoardSort;
import com.team4.hanbbyeom.matching.dto.MatchBoardItemResponse;
import com.team4.hanbbyeom.matching.dto.MatchBoardPageResponse;
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

    // 기존 필터 테스트용 — 페이지 응답에서 items만 꺼낸다 (첫 페이지, size 20)
    private List<MatchBoardItemResponse> getBoardItems(String region, String talkLevel,
                                                       Integer minDistance, Integer maxDistance,
                                                       Integer minPace, Integer maxPace,
                                                       String datePreset, Long currentUserId,
                                                       Long cursor, int size) {
        return matchRequestBoardService.getBoard(
                region, talkLevel, minDistance, maxDistance, minPace, maxPace, datePreset, currentUserId, cursor, size,
                BoardSort.LATEST
        ).items();
    }

    // 페이징 테스트용 — 새 사용자로 SEARCHING 모집글을 하나 만들고 created_at을 지정한다.
    // 사용자당 활성 모집글은 1건(uq_match_request_active_user)이라 글마다 사용자를 새로 만든다.
    // created_at을 직접 넣는 이유: 같은 트랜잭션 안의 now()는 전부 같은 값이라 정렬 순서를 가를 수 없다.
    // chk_match_request_time(created_at < search_expires_at < scheduled_at)을 만족하도록 미래 시각으로 잡는다.
    private Long createSearchingPost(OffsetDateTime createdAt) {
        return createSearchingPost(createUser("작성자"), createdAt);
    }

    private Long createSearchingPost(Long authorId, OffsetDateTime createdAt) {
        return createSearchingPost(authorId, createdAt, createdAt.plusHours(48), 5000, 8000);
    }

    // 정렬 테스트용 — 활동 시각과 거리 범위를 지정한다. chk_match_request_time(created_at < search_expires_at
    // < scheduled_at)을 만족하도록 search_expires_at은 scheduled_at 1시간 전으로 둔다.
    private Long createSearchingPost(Long authorId, OffsetDateTime createdAt, OffsetDateTime scheduledAt,
                                     int distanceMin, int distanceMax) {
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO match_request (user_id, scheduled_at, talk_level, search_expires_at, created_at)
                VALUES (?, ?, 'LIGHT_CHAT', ?, ?) RETURNING id
                """, Long.class, authorId, scheduledAt, scheduledAt.minusHours(1), createdAt);
        jdbcTemplate.update("""
                INSERT INTO run_match_condition
                    (match_request_id, course_id, meeting_point, distance_min_meters, distance_max_meters, pace_min_sec, pace_max_sec)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id, testCourseId, "뚝섬유원지역 3번 출구", distanceMin, distanceMax, 360, 400);
        return id;
    }

    private MatchBoardPageResponse page(BoardSort sort, Long cursor, int size) {
        return matchRequestBoardService.getBoard(null, null, null, null, null, null, null, -1L, cursor, size, sort);
    }

    private List<Long> ids(MatchBoardPageResponse page) {
        return page.items().stream().map(MatchBoardItemResponse::id).toList();
    }

    // userId를 "다른 글에 신청 중"인 상태로 만든다 — 아직 응답 대기(PROPOSED)인 매칭에 신청자(slot B)로 참가시킨다.
    // 신청은 apply()가 하는 것과 같이 match_request_id 없이(null) 참가 행만 만들고,
    // 이 행이 활성(released_at IS NULL)인 동안 #112의 apply() 검사와 이 이슈(#120)의 목록 제외가 같은 기준으로 걸린다.
    // 돌려주는 참가 행을 release()하면 "신청 취소/거절/종료" 상황이 된다.
    private MatchParticipant bindToPendingMatch(Long userId) {
        OffsetDateTime base = OffsetDateTime.now();
        ActivityMatch activityMatch = new ActivityMatch(
                base.plusHours(30), base.plusHours(31), TalkLevel.SILENT,
                "뚝섬 한강공원", "뚝섬 한강공원 코스", 5000, 12000,
                "뚝섬유원지역 3번 출구", 360, 400,
                base.plusHours(24), base
        );
        Long activityMatchId = activityMatchRepository.save(activityMatch).getId();
        return matchParticipantRepository.save(
                new MatchParticipant(activityMatchId, null, userId, "B", AcceptStatus.ACCEPTED));
    }

    private List<Long> boardIds() {
        return getBoardItems(null, null, null, null, null, null, null, -1L, null, 20)
                .stream().map(MatchBoardItemResponse::id).toList();
    }

    private void giveReviewToTestUser(String comment) {
        giveReviewToTestUser(comment, OffsetDateTime.now());
    }

    // testUserId(모집글 작성자)가 후기를 "받는" 상황을 만든다 — 새 리뷰어와 끝난 매칭을 하나 만들고,
    // 그 매칭에서 리뷰어가 testUserId에게 후기를 남긴 것으로 activity_review를 직접 INSERT한다.
    // activity_review는 (activity_match_id, reviewer/reviewee_user_id)가 match_participant를 참조하는
    // 복합 FK가 있어서 참가자 등록이 먼저 필요하다. 참가 행의 match_request_id는 V11부터 nullable이라
    // 별도 게시글 없이 null로 둔다.
    // 두 번 이상 호출할 수 있도록(후기 2건으로 "최신순" 검증) 참가 행은 만들자마자 release()한다 —
    // 활성(released_at IS NULL) 참가 행은 사용자당 1개만 허용되기 때문(uq_participant_active_user).
    // 해제가 다음 INSERT보다 먼저 반영되도록 saveAndFlush()를 쓴다(같은 플러시에서 INSERT가 UPDATE보다 앞선다).
    // created_at은 명시적으로 넣는다 — 같은 트랜잭션 안의 now()는 전부 같은 값이라 순서를 가를 수 없다.
    private void giveReviewToTestUser(String comment, OffsetDateTime createdAt) {
        Long reviewerId = createUser("리뷰어");

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

        MatchParticipant authorSide = matchParticipantRepository.save(
                new MatchParticipant(activityMatchId, null, testUserId, "A", AcceptStatus.ACCEPTED));
        MatchParticipant reviewerSide = matchParticipantRepository.save(
                new MatchParticipant(activityMatchId, null, reviewerId, "B", AcceptStatus.ACCEPTED));
        authorSide.release();
        reviewerSide.release();
        matchParticipantRepository.saveAndFlush(authorSide);
        matchParticipantRepository.saveAndFlush(reviewerSide);

        jdbcTemplate.update("""
                INSERT INTO activity_review
                    (activity_match_id, reviewer_user_id, reviewee_user_id, rating, perceived_talk_level, comment, created_at)
                VALUES (?, ?, ?, 5, 'SILENT', ?, ?)
                """, activityMatchId, reviewerId, testUserId, comment, createdAt);
    }

    @Test
    void 코스_필터로_조회하면_일치하는_게시글만_반환되고_작성자_신뢰정보가_join된다() {
        List<MatchBoardItemResponse> result = getBoardItems(
                "뚝섬 한강공원", null, null, null, null, null, null, testUserId + 1, null, 20 // 본인 제외되게 다른 id로 조회
        );

        assertThat(result).hasSize(1);
        MatchBoardItemResponse item = result.get(0);
        assertThat(item.courseName()).isEqualTo("뚝섬 한강공원");
        assertThat(item.author().rating()).isEqualTo(4.5);
        assertThat(item.author().completedCount()).isEqualTo(12);
    }

    @Test
    void 코스_필터가_일치하지_않으면_조회되지_않는다() {
        List<MatchBoardItemResponse> result = getBoardItems(
                "여의도 한강공원", null, null, null, null, null, null, testUserId + 1, null, 20
        );

        assertThat(result).isEmpty();
    }

    @Test
    void 거리_범위가_겹치지_않으면_조회되지_않는다() {
        List<MatchBoardItemResponse> result = getBoardItems(
                null, null, 9000, 10000, null, null, null, testUserId + 1, null, 20 // 테스트 데이터 범위(5000~8000)와 안 겹침
        );

        assertThat(result).isEmpty();
    }

    @Test
    void 페이스_범위가_겹치지_않으면_조회되지_않는다() {
        List<MatchBoardItemResponse> result = getBoardItems(
                null, null, null, null, 450, 500, null, testUserId + 1, null, 20 // 테스트 데이터 범위(360~400)와 안 겹침
        );

        assertThat(result).isEmpty();
    }

    @Test
    void 본인이_작성한_게시글은_목록에서_제외된다() {
        List<MatchBoardItemResponse> result = getBoardItems(
                null, null, null, null, null, null, null, testUserId, null, 20 // 작성자 본인으로 조회
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
        MatchBoardItemResponse.AuthorSummary fromBoard = getBoardItems(
                null, null, null, null, null, null, null, testUserId + 1, null, 20
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

        MatchBoardItemResponse.AuthorSummary fromBoard = getBoardItems(
                null, null, null, null, null, null, null, testUserId + 1, null, 20
        ).get(0).author();
        MatchBoardItemResponse.AuthorSummary fromDetail =
                matchRequestBoardService.getDetail(testMatchRequestId, testUserId).author();

        assertThat(fromDetail.latestReview()).isNotNull();
        assertThat(fromDetail.latestReview().comment()).isEqualTo("페이스 잘 맞춰주셨어요");
        assertThat(fromDetail.latestReview().createdAt()).isNotNull();
        assertThat(fromBoard.latestReview()).isEqualTo(fromDetail.latestReview());
    }

    // "최근" 후기여야 한다 — 후기가 여러 건이면 created_at이 가장 나중인 것 하나만 골라야 한다.
    // 후기 1건짜리 테스트만으로는 ORDER BY를 ASC로 바꾸거나 LIMIT을 지워도 안 깨지므로 2건을 넣는다.
    // 일부러 최신 후기를 먼저 INSERT한다(id는 작고 created_at은 뒤) — 오래된 것부터 넣으면 id 순서와
    // created_at 순서가 같아져서 ORDER BY id DESC만 남겨도 통과해버리기 때문. created_at이 1순위임을 고정.
    @Test
    void 후기가_여러_건이면_가장_최근_후기가_선택된다() {
        OffsetDateTime base = OffsetDateTime.now().minusDays(10);
        giveReviewToTestUser("최신 후기", base.plusDays(2));
        giveReviewToTestUser("오래된 후기", base.plusDays(1));

        MatchBoardItemResponse.AuthorSummary fromBoard = getBoardItems(
                null, null, null, null, null, null, null, testUserId + 1, null, 20
        ).get(0).author();
        MatchBoardItemResponse.AuthorSummary fromDetail =
                matchRequestBoardService.getDetail(testMatchRequestId, testUserId).author();

        assertThat(fromBoard.latestReview().comment()).isEqualTo("최신 후기");
        assertThat(fromDetail.latestReview().comment()).isEqualTo("최신 후기");
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

    // trust_profile 행이 없는 작성자(활동 이력 없음)의 카드 규칙 — 횟수는 0, 별점은 null(평가 없음).
    // 프로필 조회 API(TrustProfileLookupService.lookup())와 같은 규칙으로 통일 (이슈 #94).
    // 이전엔 LEFT JOIN 결과 그대로 셋 다 null이라 프로필 API(0)와 어긋났다.
    @Test
    void 신뢰_프로필이_없는_작성자는_횟수_0_별점_null로_내려준다() {
        Long postWithoutProfile = createSearchingPost(OffsetDateTime.now().minusDays(1));

        MatchBoardItemResponse.AuthorSummary fromBoard = getBoardItems(null, null, null, null, null, null, null, -1L, null, 20)
                .stream().filter(item -> item.id().equals(postWithoutProfile)).findFirst().orElseThrow().author();
        MatchBoardItemResponse.AuthorSummary fromDetail =
                matchRequestBoardService.getDetail(postWithoutProfile, -1L).author();

        assertThat(fromBoard.rating()).isNull();
        assertThat(fromBoard.completedCount()).isZero();
        assertThat(fromBoard.noShowCount()).isZero();
        assertThat(fromDetail.rating()).isNull();
        assertThat(fromDetail.completedCount()).isZero();
        assertThat(fromDetail.noShowCount()).isZero();
    }

    // ---------- 신청할 수 없는 글 제외 (이슈 #120) ----------
    // #112가 apply()에서 "작성자가 다른 신청·활동에 묶여 있으면 409"로 거절하는 글은 목록에서도 빠져야 한다.
    // 기준은 #112와 같은 match_participant.released_at IS NULL — 어긋나면 "보이는데 안 되는 글"이 생긴다.

    @Test
    void 작성자가_다른_글에_신청_중이면_그_작성자의_글은_목록에서_빠진다() {
        Long busyAuthor = createUser("신청중작성자");
        Long busyPost = createSearchingPost(busyAuthor, OffsetDateTime.now().minusHours(1));
        bindToPendingMatch(busyAuthor);

        assertThat(boardIds()).containsExactly(testMatchRequestId).doesNotContain(busyPost);
    }

    // 확정(CONFIRMED)돼도 참가 행은 그대로 활성이라 계속 빠져야 한다 — 활동이 끝나 release될 때까지.
    @Test
    void 작성자가_확정된_활동_중이어도_목록에서_빠진다() {
        Long busyAuthor = createUser("활동중작성자");
        Long busyPost = createSearchingPost(busyAuthor, OffsetDateTime.now().minusHours(1));
        MatchParticipant participation = bindToPendingMatch(busyAuthor);
        activityMatchRepository.findById(participation.getActivityMatchId()).orElseThrow().confirm("123456");

        assertThat(boardIds()).doesNotContain(busyPost);
    }

    // 신청 취소·거절·활동 종료는 모두 참가 행 release()로 끝난다 — 그 뒤엔 다시 보여야 한다.
    @Test
    void 참가가_해제되면_그_작성자의_글이_다시_보인다() {
        Long author = createUser("돌아온작성자");
        Long post = createSearchingPost(author, OffsetDateTime.now().minusHours(1));
        MatchParticipant participation = bindToPendingMatch(author);
        assertThat(boardIds()).doesNotContain(post);

        participation.release();
        matchParticipantRepository.saveAndFlush(participation);

        assertThat(boardIds()).contains(post);
    }

    // 본인 모집글만 있고 어디에도 참가하지 않은 작성자는 기존과 같이 보인다 (setUp의 testUserId가 그 경우).
    @Test
    void 참가_행이_없는_작성자의_글은_그대로_보인다() {
        Long freePost = createSearchingPost(OffsetDateTime.now().minusHours(1));

        assertThat(boardIds()).containsExactly(testMatchRequestId, freePost);
    }

    // 제외된 글은 페이지 크기 계산에서도 빠져야 한다 — size+1 조회가 제외 글을 세면 hasNext가 틀어진다.
    @Test
    void 제외된_글은_커서_페이징의_개수_계산에서도_빠진다() {
        OffsetDateTime base = OffsetDateTime.now().minusDays(1);
        Long p1 = createSearchingPost(base.plusHours(3));
        Long busyAuthor = createUser("신청중작성자");
        createSearchingPost(busyAuthor, base.plusHours(2)); // 정렬상 p1과 p3 사이 — 빠져야 함
        bindToPendingMatch(busyAuthor);
        Long p3 = createSearchingPost(base.plusHours(1));

        MatchBoardPageResponse first = matchRequestBoardService.getBoard(
                null, null, null, null, null, null, null, -1L, null, 2, BoardSort.LATEST);
        MatchBoardPageResponse second = matchRequestBoardService.getBoard(
                null, null, null, null, null, null, null, -1L, first.nextCursor(), 2, BoardSort.LATEST);

        assertThat(first.items()).extracting(MatchBoardItemResponse::id).containsExactly(testMatchRequestId, p1);
        assertThat(first.hasNext()).isTrue();
        assertThat(second.items()).extracting(MatchBoardItemResponse::id).containsExactly(p3);
        assertThat(second.hasNext()).isFalse();
    }

    // ---------- 정렬 옵션 (이슈 #123) ----------
    // setUp의 testMatchRequestId는 created_at=now, scheduled_at=now+48h, 거리 5000~8000이다.

    // 활동 날짜 빠른 순. 일부러 가장 가까운 활동을 가장 나중에 INSERT해서(id는 크고 scheduled_at은 앞) id 순서와
    // 어긋나게 둔다 — id ASC만으로는 통과 못 하고 scheduled_at이 1순위일 때만 통과.
    @Test
    void SCHEDULED는_활동_날짜_빠른_순이고_동시각이면_id_오름차순이다() {
        OffsetDateTime created = OffsetDateTime.now().minusHours(1);
        OffsetDateTime day = OffsetDateTime.now().plusDays(3);
        Long later = createSearchingPost(createUser("늦은활동"), created, day.plusHours(5), 5000, 8000);
        Long soonA = createSearchingPost(createUser("빠른활동가"), created, day, 5000, 8000);
        Long soonB = createSearchingPost(createUser("빠른활동나"), created, day, 5000, 8000); // soonA와 동시각, id 더 큼 (이메일 체크 제약 때문에 라벨은 소문자·한글만)

        // testMatchRequestId(now+48h) < soonA(day, id 작음) < soonB(day) < later(day+5h)
        assertThat(ids(page(BoardSort.SCHEDULED, null, 20))).containsExactly(testMatchRequestId, soonA, soonB, later);
    }

    // 거리 짧은 순 — 최소 거리 기준, 같으면 최대 거리, 그다음 id. 가장 짧은 글을 가장 나중에 INSERT.
    @Test
    void DISTANCE는_최소_거리_짧은_순이고_같으면_최대_거리_그다음_id_순이다() {
        OffsetDateTime created = OffsetDateTime.now().minusHours(1);
        OffsetDateTime day = OffsetDateTime.now().plusDays(3);
        Long longRun = createSearchingPost(createUser("장거리"), created, day, 10000, 15000);
        Long shortWide = createSearchingPost(createUser("단거리넓게"), created, day, 3000, 8000);
        Long shortNarrow = createSearchingPost(createUser("단거리좁게"), created, day, 3000, 5000); // min 같음, max 짧음, id 큼
        Long shortNarrow2 = createSearchingPost(createUser("단거리좁게2"), created, day, 3000, 5000); // 완전 동일, id로 구분

        // shortNarrow(3000,5000) < shortNarrow2(3000,5000, id 큼) < shortWide(3000,8000) < testMatchRequestId(5000,8000) < longRun
        assertThat(ids(page(BoardSort.DISTANCE, null, 20)))
                .containsExactly(shortNarrow, shortNarrow2, shortWide, testMatchRequestId, longRun);
    }

    // sort 생략/LATEST는 #103과 동일 (회귀 가드) — 최신 등록순.
    @Test
    void LATEST는_기존과_같이_최신_등록순이다() {
        OffsetDateTime base = OffsetDateTime.now().minusDays(1);
        Long newer = createSearchingPost(base.plusHours(2));
        Long older = createSearchingPost(base.plusHours(1));

        assertThat(ids(page(BoardSort.LATEST, null, 20))).containsExactly(testMatchRequestId, newer, older);
    }

    // 정렬별 커서 이어받기 — 동시각/동거리 글이 페이지 경계에 걸려도 중복·누락 없이 이어진다.
    // 커서 비교가 정렬 키 전체(행 비교)가 아니라 id만 보면 여기서 깨진다. 그래서 일부러 "활동 시각은 뒤인데
    // id는 작은" 글(x)을 첫 번째로 INSERT한다 — id만 비교하면 커서(y1) 뒤에서 x가 통째로 사라진다.
    @Test
    void SCHEDULED_커서는_동시각_경계와_id_역순에서도_정확히_이어진다() {
        OffsetDateTime created = OffsetDateTime.now().minusHours(1);
        OffsetDateTime day = OffsetDateTime.now().plusDays(3);
        Long x = createSearchingPost(createUser("x"), created, day.plusHours(2), 5000, 8000); // id 가장 작음, 시각은 가장 뒤
        Long y1 = createSearchingPost(createUser("y1"), created, day, 5000, 8000);
        Long y2 = createSearchingPost(createUser("y2"), created, day, 5000, 8000);           // y1과 동시각
        Long z = createSearchingPost(createUser("z"), created, day.plusHours(1), 5000, 8000);
        // 순서: testMatchRequestId(now+48h), y1, y2, z, x

        MatchBoardPageResponse first = page(BoardSort.SCHEDULED, null, 2);
        MatchBoardPageResponse second = page(BoardSort.SCHEDULED, first.nextCursor(), 2);
        MatchBoardPageResponse third = page(BoardSort.SCHEDULED, second.nextCursor(), 2);

        assertThat(ids(first)).containsExactly(testMatchRequestId, y1);
        assertThat(ids(second)).containsExactly(y2, z);
        assertThat(ids(third)).containsExactly(x);
        assertThat(third.hasNext()).isFalse();
    }

    @Test
    void DISTANCE_커서는_동거리_경계와_id_역순에서도_정확히_이어진다() {
        OffsetDateTime created = OffsetDateTime.now().minusHours(1);
        OffsetDateTime day = OffsetDateTime.now().plusDays(3);
        Long x = createSearchingPost(createUser("x"), created, day, 4000, 6000); // id 가장 작음, 거리는 뒤쪽
        Long a = createSearchingPost(createUser("a"), created, day, 3000, 5000);
        Long b = createSearchingPost(createUser("b"), created, day, 3000, 5000); // a와 동거리
        Long c = createSearchingPost(createUser("c"), created, day, 3000, 5000);
        // 순서: a, b, c (동거리 → id ASC), x(4000~6000), testMatchRequestId(5000~8000)

        MatchBoardPageResponse first = page(BoardSort.DISTANCE, null, 2);
        MatchBoardPageResponse second = page(BoardSort.DISTANCE, first.nextCursor(), 2);
        MatchBoardPageResponse third = page(BoardSort.DISTANCE, second.nextCursor(), 2);

        assertThat(ids(first)).containsExactly(a, b);
        assertThat(ids(second)).containsExactly(c, x);
        assertThat(ids(third)).containsExactly(testMatchRequestId);
        assertThat(third.hasNext()).isFalse();
    }

    // 필터 + 정렬 조합 — 필터로 걸러진 결과 안에서만 정렬·페이징된다.
    @Test
    void 필터와_정렬을_함께_쓰면_필터_결과_안에서_정렬된다() {
        OffsetDateTime created = OffsetDateTime.now().minusHours(1);
        OffsetDateTime day = OffsetDateTime.now().plusDays(3);
        Long silentShort = jdbcTemplate.queryForObject("""
                INSERT INTO match_request (user_id, scheduled_at, talk_level, search_expires_at, created_at)
                VALUES (?, ?, 'SILENT', ?, ?) RETURNING id
                """, Long.class, createUser("조용히짧게"), day, day.minusHours(1), created);
        jdbcTemplate.update("""
                INSERT INTO run_match_condition
                    (match_request_id, course_id, meeting_point, distance_min_meters, distance_max_meters, pace_min_sec, pace_max_sec)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, silentShort, testCourseId, "뚝섬유원지역 3번 출구", 3000, 5000, 360, 400);
        createSearchingPost(createUser("수다짧게"), created, day, 2000, 4000); // LIGHT_CHAT — 필터에 걸려야 함

        List<Long> result = matchRequestBoardService.getBoard(
                null, "SILENT", null, null, null, null, null, -1L, null, 20, BoardSort.DISTANCE)
                .items().stream().map(MatchBoardItemResponse::id).toList();

        assertThat(result).containsExactly(silentShort);
    }

    // #120 제외 조건은 정렬과 무관하게 유지된다.
    @Test
    void 정렬을_바꿔도_신청_중인_작성자의_글은_빠진다() {
        OffsetDateTime created = OffsetDateTime.now().minusHours(1);
        OffsetDateTime day = OffsetDateTime.now().plusDays(3);
        Long busyAuthor = createUser("신청중작성자");
        Long busyPost = createSearchingPost(busyAuthor, created, day.minusDays(2), 1000, 2000); // 어느 정렬이든 맨 앞일 값
        bindToPendingMatch(busyAuthor);

        assertThat(ids(page(BoardSort.SCHEDULED, null, 20))).doesNotContain(busyPost);
        assertThat(ids(page(BoardSort.DISTANCE, null, 20))).doesNotContain(busyPost);
    }

    @Test
    void 잘못된_sort_값은_거부한다() {
        assertThatThrownBy(() -> BoardSort.from("NEWEST"))
                .isInstanceOf(InvalidRequestValueException.class)
                .hasMessageContaining("sort");
        assertThat(BoardSort.from("distance")).isEqualTo(BoardSort.DISTANCE); // 대소문자 무관
    }

    // ---------- 커서 페이징 (이슈 #103) ----------

    // 정렬 고정: created_at DESC, id DESC. 일부러 오래된 글을 나중에 INSERT해서(id는 크고 created_at은 앞)
    // id 순서와 created_at 순서를 어긋나게 둔다 — ORDER BY id DESC만으로는 통과 못 하고 created_at이
    // 1순위일 때만 통과. setUp의 testMatchRequestId(created_at=now)가 가장 최신이라 맨 앞에 온다.
    @Test
    void 목록은_created_at_내림차순_동시각이면_id_내림차순으로_정렬된다() {
        OffsetDateTime base = OffsetDateTime.now().minusDays(1);
        Long newer = createSearchingPost(base.plusHours(2));
        Long older = createSearchingPost(base.plusHours(1));
        Long sameTimeAsOlder = createSearchingPost(base.plusHours(1)); // older와 동시각, id는 더 큼

        List<MatchBoardItemResponse> items = getBoardItems(null, null, null, null, null, null, null, -1L, null, 20);

        assertThat(items).extracting(MatchBoardItemResponse::id)
                .containsExactly(testMatchRequestId, newer, sameTimeAsOlder, older);
    }

    // size만큼만 내려주고, 더 있으면 hasNext=true + nextCursor=마지막 항목 id. 정확히 size건이면 hasNext=false.
    @Test
    void size만큼_잘라서_내려주고_다음_페이지_여부를_알려준다() {
        OffsetDateTime base = OffsetDateTime.now().minusDays(1);
        Long p1 = createSearchingPost(base.plusHours(3));
        Long p2 = createSearchingPost(base.plusHours(2));
        createSearchingPost(base.plusHours(1));
        // 총 4건 (setUp 1건 + 3건)

        MatchBoardPageResponse page = matchRequestBoardService.getBoard(
                null, null, null, null, null, null, null, -1L, null, 3, BoardSort.LATEST);

        assertThat(page.items()).extracting(MatchBoardItemResponse::id).containsExactly(testMatchRequestId, p1, p2);
        assertThat(page.hasNext()).isTrue();
        assertThat(page.nextCursor()).isEqualTo(p2);

        MatchBoardPageResponse exact = matchRequestBoardService.getBoard(
                null, null, null, null, null, null, null, -1L, null, 4, BoardSort.LATEST);
        assertThat(exact.items()).hasSize(4);
        assertThat(exact.hasNext()).isFalse();
        assertThat(exact.nextCursor()).isNull();
    }

    // cursor로 이어 받으면 이전 페이지와 겹치지 않고 빠지는 글도 없다. 두 페이지 사이에 새 글이 들어와도
    // (오프셋 방식이면 한 칸씩 밀려 중복이 생기는 상황) 커서는 "이 글 다음부터"라 영향이 없다.
    @Test
    void cursor로_다음_페이지를_받으면_중복도_누락도_없고_새_글이_끼어들어도_영향이_없다() {
        OffsetDateTime base = OffsetDateTime.now().minusDays(1);
        Long p1 = createSearchingPost(base.plusHours(4));
        Long p2 = createSearchingPost(base.plusHours(3));
        Long p3 = createSearchingPost(base.plusHours(2));
        Long p4 = createSearchingPost(base.plusHours(1));

        MatchBoardPageResponse first = matchRequestBoardService.getBoard(
                null, null, null, null, null, null, null, -1L, null, 2, BoardSort.LATEST);
        assertThat(first.items()).extracting(MatchBoardItemResponse::id).containsExactly(testMatchRequestId, p1);

        // 첫 페이지를 받은 뒤 최신 글이 하나 더 올라온 상황
        createSearchingPost(OffsetDateTime.now().plusMinutes(1));

        MatchBoardPageResponse second = matchRequestBoardService.getBoard(
                null, null, null, null, null, null, null, -1L, first.nextCursor(), 2, BoardSort.LATEST);
        assertThat(second.items()).extracting(MatchBoardItemResponse::id).containsExactly(p2, p3);

        MatchBoardPageResponse third = matchRequestBoardService.getBoard(
                null, null, null, null, null, null, null, -1L, second.nextCursor(), 2, BoardSort.LATEST);
        assertThat(third.items()).extracting(MatchBoardItemResponse::id).containsExactly(p4);
        assertThat(third.hasNext()).isFalse();
    }

    // 동시각 글이 페이지 경계에 걸려도 (created_at, id) 행 비교라 정확히 이어진다 — id만 비교하면
    // 여기서 중복/누락이 생긴다.
    @Test
    void 동시각_글이_페이지_경계에_걸려도_정확히_이어진다() {
        OffsetDateTime sameTime = OffsetDateTime.now().minusDays(1);
        Long a = createSearchingPost(sameTime);
        Long b = createSearchingPost(sameTime);
        Long c = createSearchingPost(sameTime);
        // 정렬: testMatchRequestId, c, b, a (동시각은 id DESC)

        MatchBoardPageResponse first = matchRequestBoardService.getBoard(
                null, null, null, null, null, null, null, -1L, null, 2, BoardSort.LATEST);
        MatchBoardPageResponse second = matchRequestBoardService.getBoard(
                null, null, null, null, null, null, null, -1L, first.nextCursor(), 2, BoardSort.LATEST);

        assertThat(first.items()).extracting(MatchBoardItemResponse::id).containsExactly(testMatchRequestId, c);
        assertThat(second.items()).extracting(MatchBoardItemResponse::id).containsExactly(b, a);
        assertThat(second.hasNext()).isFalse();
    }

    // 필터와 커서를 같이 써도 필터 결과 안에서만 이어진다.
    @Test
    void 필터와_cursor를_함께_쓰면_필터_결과_안에서_이어진다() {
        OffsetDateTime base = OffsetDateTime.now().minusDays(1);
        Long p1 = createSearchingPost(base.plusHours(2));
        Long p2 = createSearchingPost(base.plusHours(1));

        MatchBoardPageResponse first = matchRequestBoardService.getBoard(
                "뚝섬 한강공원", null, null, null, null, null, null, -1L, null, 2, BoardSort.LATEST);
        MatchBoardPageResponse second = matchRequestBoardService.getBoard(
                "뚝섬 한강공원", null, null, null, null, null, null, -1L, first.nextCursor(), 2, BoardSort.LATEST);

        assertThat(first.items()).extracting(MatchBoardItemResponse::id).containsExactly(testMatchRequestId, p1);
        assertThat(second.items()).extracting(MatchBoardItemResponse::id).containsExactly(p2);

        MatchBoardPageResponse other = matchRequestBoardService.getBoard(
                "여의도 한강공원", null, null, null, null, null, null, -1L, null, 2, BoardSort.LATEST);
        assertThat(other.items()).isEmpty();
        assertThat(other.hasNext()).isFalse();
    }

    @Test
    void size가_범위_밖이면_거부한다() {
        assertThatThrownBy(() -> matchRequestBoardService.getBoard(
                null, null, null, null, null, null, null, -1L, null, 0, BoardSort.LATEST))
                .isInstanceOf(InvalidRequestValueException.class);
        assertThatThrownBy(() -> matchRequestBoardService.getBoard(
                null, null, null, null, null, null, null, -1L, null, MatchRequestBoardService.BOARD_MAX_PAGE_SIZE + 1, BoardSort.LATEST))
                .isInstanceOf(InvalidRequestValueException.class);
    }

    // 존재하지 않는 cursor는 별도 검증(추가 쿼리) 없이 빈 페이지로 끝난다 — 프론트는 hasNext=false에서 멈춘다.
    @Test
    void 존재하지_않는_cursor는_빈_페이지를_돌려준다() {
        MatchBoardPageResponse page = matchRequestBoardService.getBoard(
                null, null, null, null, null, null, null, -1L, 999_999_999L, 20, BoardSort.LATEST);

        assertThat(page.items()).isEmpty();
        assertThat(page.hasNext()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }
}
