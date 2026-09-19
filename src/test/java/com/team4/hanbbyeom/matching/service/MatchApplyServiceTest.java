package com.team4.hanbbyeom.matching.service;

import com.team4.hanbbyeom.matching.domain.*;
import com.team4.hanbbyeom.matching.dto.MatchBoardItemResponse;
import com.team4.hanbbyeom.matching.dto.MatchRequestCreateRequest;
import com.team4.hanbbyeom.matching.dto.MyApplicationResponse;
import com.team4.hanbbyeom.matching.exception.InvalidMatchRequestException;
import com.team4.hanbbyeom.matching.exception.MatchRequestNotSearchingException;
import com.team4.hanbbyeom.matching.repository.ActivityMatchRepository;
import com.team4.hanbbyeom.matching.repository.MatchParticipantRepository;
import com.team4.hanbbyeom.matching.repository.MatchRequestRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;

@SpringBootTest
@Transactional
class MatchApplyServiceTest {

    @Autowired private MatchApplyService matchApplyService;
    @Autowired private MatchRequestCommandService matchRequestCommandService;
    @Autowired private MatchDecisionService matchDecisionService;
    @Autowired private MatchRequestRepository matchRequestRepository;
    @Autowired private ActivityMatchRepository activityMatchRepository;
    @Autowired private MatchParticipantRepository matchParticipantRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @PersistenceContext private EntityManager entityManager;

    private Long hostUserId, applicantUserId, hostRequestId, courseId;

    @BeforeEach
    void setUp() {
        hostUserId = createUser("host");
        applicantUserId = createUser("applicant");

        courseId = jdbcTemplate.queryForObject(
                "SELECT id FROM running_course WHERE name = ? LIMIT 1", Long.class, "뚝섬 한강공원");

        MatchRequest hostRequest = new MatchRequest(
                hostUserId, OffsetDateTime.now().plusHours(48), TalkLevel.LIGHT_CHAT,
                OffsetDateTime.now().plusHours(9)
        );
        hostRequestId = matchRequestRepository.save(hostRequest).getId();

        jdbcTemplate.update(
                """
                INSERT INTO run_match_condition
                    (match_request_id, course_id, meeting_point, distance_min_meters, distance_max_meters, pace_min_sec, pace_max_sec)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                hostRequestId, courseId, "뚝섬유원지역 3번 출구", 5000, 8000, 360, 400
        );
    }

    private Long createUser(String label) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, nickname, email_verified_at) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class,
                label + "-" + System.nanoTime() + "@example.com", "dummy-hash", label, OffsetDateTime.now()
        );
    }

    // getMyApplications() 테스트가 여러 호스트 게시글을 만들 때 쓰는, setUp()과 동일한 패턴의 헬퍼.
    private Long createHostRequest(Long ownerUserId) {
        MatchRequest request = new MatchRequest(
                ownerUserId, OffsetDateTime.now().plusHours(48), TalkLevel.LIGHT_CHAT,
                OffsetDateTime.now().plusHours(9)
        );
        Long requestId = matchRequestRepository.save(request).getId();
        jdbcTemplate.update(
                """
                INSERT INTO run_match_condition
                    (match_request_id, course_id, meeting_point, distance_min_meters, distance_max_meters, pace_min_sec, pace_max_sec)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                requestId, courseId, "뚝섬유원지역 3번 출구", 5000, 8000, 360, 400
        );
        return requestId;
    }

    @Test
    void 신청자에게_본인_모집글이_없어도_신청이_성공한다() {
        Long activityMatchId = matchApplyService.apply(applicantUserId, hostRequestId);

        assertThat(activityMatchId).isNotNull();
        MatchRequest updatedHost = matchRequestRepository.findById(hostRequestId).orElseThrow();
        assertThat(updatedHost.getStatus()).isEqualTo(MatchRequestStatus.PENDING_CONFIRMATION);
    }

    @Test
    void 신청자에게_본인_모집글이_있어도_그_상태는_그대로_유지된다() {
        MatchRequest applicantRequest = new MatchRequest(
                applicantUserId, OffsetDateTime.now().plusHours(10), TalkLevel.LIGHT_CHAT,
                OffsetDateTime.now().plusHours(9)
        );
        Long applicantRequestId = matchRequestRepository.save(applicantRequest).getId();

        matchApplyService.apply(applicantUserId, hostRequestId);

        MatchRequest updatedHost = matchRequestRepository.findById(hostRequestId).orElseThrow();
        MatchRequest updatedApplicant = matchRequestRepository.findById(applicantRequestId).orElseThrow();

        assertThat(updatedHost.getStatus()).isEqualTo(MatchRequestStatus.PENDING_CONFIRMATION);
        // 신청자 본인의 게시글은 이번 신청과 무관하게 그대로 SEARCHING으로 유지된다
        // (신청은 이제 신청자 본인 게시글과 완전히 분리된 동작).
        assertThat(updatedApplicant.getStatus()).isEqualTo(MatchRequestStatus.SEARCHING);
    }

    @Test
    void 신청을_취소하면_참여_연결이_해제되어_같은_사람이_다시_참여할_수_있다() {
        Long activityMatchId = matchApplyService.apply(applicantUserId, hostRequestId);

        matchApplyService.cancelApplication(applicantUserId, hostRequestId);

        var participants = matchParticipantRepository.findByActivityMatchId(activityMatchId);
        // released_at이 안 채워지면 uq_participant_active_user 제약 때문에 이 두 사람은
        // 다시는 어떤 매칭에도 참여할 수 없게 된다 — 그걸 막는 회귀 테스트.
        assertThat(participants).allSatisfy(p -> assertThat(p.getReleasedAt()).isNotNull());
    }

    // 회귀 테스트: 게시글 등록(최소 리드타임 3시간)과 신청 가능 기한(24시간) 사이의 정책
    // 모순 — 3~24시간 뒤 일정 글은 등록은 되지만 아무도 신청할 수 없던 문제(팀원 1차 리뷰로
    // 발견). 처음엔 등록 최소 리드타임을 25시간으로 올려서 고쳤었으나, 그러면 당일 매칭
    // ("오늘 저녁 같이 뛸 사람 구하기")이라는 핵심 시나리오 자체가 막혀버린다는 2차 리뷰
    // 피드백을 받고 방향을 바꿨다. 등록 최소 리드타임(MIN_LEAD_HOURS=3시간)은 그대로 두고,
    // 대신 apply()가 호스트 응답 기한을 "최대 24시간, 활동 시작 1시간 전을 넘지 않도록"
    // 동적으로 계산하도록 고쳐서, 등록 가능한 모든 리드타임에서 등록 직후 바로 신청까지
    // 가능해야 한다.
    @Test
    void 최소_리드타임으로_등록해도_등록_직후_바로_신청할_수_있다() {
        MatchRequestCreateRequest sameDayRequest = new MatchRequestCreateRequest(
                courseId, "뚝섬유원지역 3번 출구", 5000, 8000, 360, 400,
                OffsetDateTime.now().plusHours(3).plusMinutes(5), "SILENT" // MIN_LEAD_HOURS(3시간) 경계에 너무 딱 붙지 않게 여유를 둔다
        );
        Long newHostUserId = createUser("host-sameday");
        Long newHostRequestId = matchRequestCommandService.create(newHostUserId, sameDayRequest);
        // RunMatchCondition은 @MapsId라 save() 시점에 즉시 INSERT되지 않고 flush까지 지연될 수
        // 있는데, apply()가 이걸 raw SQL로 직접 조회하므로 명시적으로 flush해서 보이게 한다
        // (MatchingFlowIntegrationTest와 동일한 이유 — 실제 운영에서는 create()와 apply()가
        // 항상 별개 트랜잭션이라 이 문제가 없음, 테스트에서만 필요).
        entityManager.flush();

        Long activityMatchId = matchApplyService.apply(applicantUserId, newHostRequestId);

        assertThat(activityMatchId).isNotNull();
    }

    // 위와 같은 이유로, 예전 1차 수정(MIN_LEAD_HOURS=25시간) 이전에 "죽은 구간"이었던
    // 리드타임(10시간)도 이제 등록과 신청이 모두 성공해야 한다.
    @Test
    void 예전에_죽은_구간이었던_리드타임도_등록과_신청이_모두_성공한다() {
        MatchRequestCreateRequest deadZoneRequest = new MatchRequestCreateRequest(
                courseId, "뚝섬유원지역 3번 출구", 5000, 8000, 360, 400,
                OffsetDateTime.now().plusHours(10), "SILENT"
        );
        Long newHostUserId = createUser("host-deadzone");
        Long newHostRequestId = matchRequestCommandService.create(newHostUserId, deadZoneRequest);
        entityManager.flush();

        Long activityMatchId = matchApplyService.apply(applicantUserId, newHostRequestId);

        assertThat(activityMatchId).isNotNull();
    }

    // 호스트 응답 기한이 실제로 "최대 24시간, 활동 시작 1시간 전을 넘지 않도록" 계산되는지
    // 직접 확인한다 — 임박한 일정(3시간 뒤)은 응답 기한이 24시간 뒤가 아니라 활동 시작
    // 1시간 전(약 2시간 뒤)으로 짧게 잡혀야 한다.
    @Test
    void 임박한_일정은_응답_기한이_활동_시작_1시간_전으로_짧게_잡힌다() {
        // MIN_LEAD_HOURS(3시간) 경계에 너무 딱 붙으면 create() 실행 시점의 now()가 살짝 더
        // 늦어져서 검증에 걸릴 수 있으므로 여유를 둔다.
        OffsetDateTime scheduledAt = OffsetDateTime.now().plusHours(3).plusMinutes(5);
        MatchRequestCreateRequest sameDayRequest = new MatchRequestCreateRequest(
                courseId, "뚝섬유원지역 3번 출구", 5000, 8000, 360, 400,
                scheduledAt, "SILENT"
        );
        Long newHostUserId = createUser("host-shortwindow");
        Long newHostRequestId = matchRequestCommandService.create(newHostUserId, sameDayRequest);
        entityManager.flush();

        Long activityMatchId = matchApplyService.apply(applicantUserId, newHostRequestId);

        ActivityMatch activityMatch = activityMatchRepository.findById(activityMatchId).orElseThrow();
        assertThat(activityMatch.getDecisionExpiresAt())
                .isCloseTo(scheduledAt.minusHours(1), within(2, ChronoUnit.SECONDS));
    }

    // 반대로 충분히 먼 일정(200시간 뒤)은 원래대로 24시간을 다 써야 한다.
    @Test
    void 충분히_먼_일정은_응답_기한이_24시간_전체를_쓴다() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime scheduledAt = now.plusHours(200);
        MatchRequestCreateRequest farAwayRequest = new MatchRequestCreateRequest(
                courseId, "뚝섬유원지역 3번 출구", 5000, 8000, 360, 400,
                scheduledAt, "SILENT"
        );
        Long newHostUserId = createUser("host-fullwindow");
        Long newHostRequestId = matchRequestCommandService.create(newHostUserId, farAwayRequest);
        entityManager.flush();

        Long activityMatchId = matchApplyService.apply(applicantUserId, newHostRequestId);

        ActivityMatch activityMatch = activityMatchRepository.findById(activityMatchId).orElseThrow();
        assertThat(activityMatch.getDecisionExpiresAt())
                .isCloseTo(now.plusHours(24), within(2, ChronoUnit.SECONDS));
    }

    // 회귀 테스트: decisionExpiresAt이 scheduledAt-1h까지 내려올 수 있게 되면서(위 두 테스트),
    // apply()가 가드에 쓴 now와 ActivityMatch가 실제로 저장하는 created_at이 서로 다른 시각이면
    // (엔티티 생성자가 내부에서 OffsetDateTime.now()를 다시 호출했었음) 그 미세한 시간차만으로
    // created_at < decision_expires_at DB 제약을 위반해 500이 날 수 있었다(팀원 리뷰로 발견한
    // 회귀 — 기존 버그가 아니라 이 PR이 새로 들여온 문제). scheduledAt을 1시간을 살짝 넘는
    // 정도로만 잡아서(등록 최소 리드타임 3시간은 여기서 검증 대상이 아니므로 서비스가 아니라
    // 엔티티를 직접 저장해 우회) decisionExpiresAt이 now에 거의 붙는 가장 빡빡한 경우를
    // 재현한다 — 지금은 ActivityMatch 생성자가 now를 다시 부르지 않고 apply()가 넘겨준 값을
    // 그대로 쓰므로, 타이밍과 무관하게 항상 성공해야 한다.
    @Test
    void 응답_기한이_활동_시작_바로_1시간_전으로_바짝_붙어도_예외없이_성공한다() {
        Long tightHostUserId = createUser("host-tightwindow");
        OffsetDateTime scheduledAt = OffsetDateTime.now().plusHours(1).plusMinutes(2);
        MatchRequest tightHostRequest = new MatchRequest(
                tightHostUserId, scheduledAt, TalkLevel.LIGHT_CHAT, scheduledAt.minusMinutes(1)
        );
        Long tightHostRequestId = matchRequestRepository.save(tightHostRequest).getId();
        jdbcTemplate.update(
                """
                INSERT INTO run_match_condition
                    (match_request_id, course_id, meeting_point, distance_min_meters, distance_max_meters, pace_min_sec, pace_max_sec)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                tightHostRequestId, courseId, "뚝섬유원지역 3번 출구", 5000, 8000, 360, 400
        );

        Long activityMatchId = matchApplyService.apply(applicantUserId, tightHostRequestId);

        assertThat(activityMatchId).isNotNull();
    }

    // 화면(26 "내가 신청한 모집")의 탭 구성(전체/대기 중/수락됨/거절됨/취소함)에 맞춰, 같은
    // 신청자가 넣은 서로 다른 결과의 신청들이 각각 올바른 표시 상태로 매핑되는지, 그리고
    // status 필터가 정확히 그 건만 걸러내는지 확인한다.
    //
    // ACCEPTED는 별도 신청자로 검증한다(아래 다른 테스트) — uq_participant_active_user
    // 부분 유니크 인덱스 때문에 한 유저는 "아직 안 풀린(released_at이 null인)" 참여를
    // 동시에 2개 가질 수 없는데, PENDING과 ACCEPTED(=CONFIRMED) 둘 다 release()를 안 하는
    // 상태라 같은 신청자로 이 둘을 동시에 만들 수 없다. REJECTED/CANCELLED는 release()를
    // 하므로 PENDING 하나와 함께 같은 신청자에게 공존할 수 있다.
    @Test
    void 신청_내역_목록에_각_결과가_올바른_상태로_매핑되고_필터링된다() {
        // REJECTED: 신청 후 호스트가 거절 — release()되어 참여 슬롯이 풀린다.
        Long rejectedHostUserId = createUser("host-rejected");
        Long rejectedHostRequestId = createHostRequest(rejectedHostUserId);
        Long rejectedId = matchApplyService.apply(applicantUserId, rejectedHostRequestId);
        matchDecisionService.reject(rejectedHostUserId, rejectedId);
        // MatchParticipant가 IDENTITY 전략이라 다음 apply()의 참여자 INSERT가 즉시 실행되는데,
        // 그 전에 방금 release()로 세팅한 released_at UPDATE가 먼저 DB에 반영돼 있어야
        // uq_participant_active_user 제약을 안 어긴다. 실제 운영에서는 reject()와 apply()가
        // 항상 별개 트랜잭션(별개 HTTP 요청)이라 이 순서가 자동으로 보장되지만, 테스트는 하나의
        // 트랜잭션을 공유하므로 명시적으로 flush해서 순서를 맞춰준다.
        entityManager.flush();

        // CANCELLED: 신청 후 신청자 본인이 취소 — 마찬가지로 release()되어 슬롯이 풀린다.
        Long cancelledHostUserId = createUser("host-cancelled");
        Long cancelledHostRequestId = createHostRequest(cancelledHostUserId);
        Long cancelledId = matchApplyService.apply(applicantUserId, cancelledHostRequestId);
        matchApplyService.cancelApplication(applicantUserId, cancelledHostRequestId);
        entityManager.flush();

        // PENDING: setUp()의 hostRequestId에 신청만 하고 아무도 응답하지 않은 상태 —
        // 위 두 건이 이미 release()됐으므로 이 신청이 유일한 "활성" 참여로 남는다.
        Long pendingId = matchApplyService.apply(applicantUserId, hostRequestId);

        List<MyApplicationResponse> all = matchApplyService.getMyApplications(applicantUserId, null);
        assertThat(all)
                .extracting(MyApplicationResponse::activityMatchId, MyApplicationResponse::status)
                .containsExactlyInAnyOrder(
                        tuple(pendingId, "PENDING"),
                        tuple(rejectedId, "REJECTED"),
                        tuple(cancelledId, "CANCELLED")
                );

        // 회귀 테스트: 신청 취소(POST /api/matching/board/{requestId}/apply/cancel)에 필요한
        // 건 activityMatchId가 아니라 호스트 게시글 id인데, 응답에 이게 빠져 있어서 새로고침 후
        // 대기 중 신청을 취소할 방법이 없었다(팀원 리뷰로 발견). PENDING 건의 hostMatchRequestId가
        // 실제로 취소 API가 받는 그 게시글 id(hostRequestId)와 일치하는지 확인한다.
        assertThat(all)
                .filteredOn(r -> r.activityMatchId().equals(pendingId))
                .extracting(MyApplicationResponse::hostMatchRequestId)
                .containsExactly(hostRequestId);

        assertThat(matchApplyService.getMyApplications(applicantUserId, "PENDING"))
                .extracting(MyApplicationResponse::activityMatchId).containsExactly(pendingId);
        assertThat(matchApplyService.getMyApplications(applicantUserId, "REJECTED"))
                .extracting(MyApplicationResponse::activityMatchId).containsExactly(rejectedId);
        assertThat(matchApplyService.getMyApplications(applicantUserId, "CANCELLED"))
                .extracting(MyApplicationResponse::activityMatchId).containsExactly(cancelledId);
    }

    // 회귀 테스트: status에 오타 등 알 수 없는 값이 오면 빈 배열(200)이 아니라 400으로 막아야
    // 한다 — 안 그러면 "신청 내역이 없음"과 "필터 값이 잘못됨"이 구분 안 돼서 디버깅이
    // 어렵다(팀원 리뷰로 발견).
    @Test
    void 유효하지_않은_status_필터는_예외가_발생한다() {
        assertThatThrownBy(() -> matchApplyService.getMyApplications(applicantUserId, "FOO"))
                .isInstanceOf(InvalidMatchRequestException.class);
    }

    @Test
    void 수락된_신청은_ACCEPTED로_매핑된다() {
        // 위 테스트와 달리 별도 신청자를 쓴다 — CONFIRMED도 release()를 안 하는 상태라서,
        // 같은 신청자에게 PENDING과 동시에 공존시킬 수 없기 때문(테스트 격리 목적).
        Long acceptedApplicantUserId = createUser("applicant-accepted");
        Long acceptedHostUserId = createUser("host-accepted");
        Long acceptedHostRequestId = createHostRequest(acceptedHostUserId);
        Long acceptedId = matchApplyService.apply(acceptedApplicantUserId, acceptedHostRequestId);
        matchDecisionService.accept(acceptedHostUserId, acceptedId);

        assertThat(matchApplyService.getMyApplications(acceptedApplicantUserId, null))
                .extracting(MyApplicationResponse::activityMatchId, MyApplicationResponse::status)
                .containsExactly(tuple(acceptedId, "ACCEPTED"));
        assertThat(matchApplyService.getMyApplications(acceptedApplicantUserId, "ACCEPTED"))
                .extracting(MyApplicationResponse::activityMatchId).containsExactly(acceptedId);
    }

    // 이슈 #70: 내 신청 내역의 호스트 카드도 모집 탭 목록/상세와 같은 AuthorSummary를 쓰므로, 노쇼 횟수와
    // 최근 후기가 같은 방식으로 채워져야 한다. 세 화면 중 이 경로만 검증이 빠져 있어 추가.
    @Test
    void 내_신청_내역의_호스트_카드에_노쇼_횟수와_최근_후기가_채워진다() {
        jdbcTemplate.update(
                "INSERT INTO trust_profile (user_id, average_rating, no_show_report_count) VALUES (?, ?, ?)",
                hostUserId, 4.0, 2
        );
        giveReviewToHost("페이스 잘 맞춰주셨어요");

        matchApplyService.apply(applicantUserId, hostRequestId);

        MatchBoardItemResponse.AuthorSummary host =
                matchApplyService.getMyApplications(applicantUserId, null).get(0).host();
        assertThat(host.rating()).isEqualTo(4.0);
        assertThat(host.noShowCount()).isEqualTo(2);
        assertThat(host.latestReview()).isNotNull();
        assertThat(host.latestReview().comment()).isEqualTo("페이스 잘 맞춰주셨어요");
        assertThat(host.latestReview().createdAt()).isNotNull();
    }

    // 호스트가 "과거에 후기를 받은" 상태를 만든다 — 새 리뷰어와 이미 끝난 매칭을 하나 만들고 리뷰어가 호스트에게
    // 후기를 남긴 것으로 activity_review를 직접 INSERT한다(복합 FK 때문에 두 사람 모두 그 매칭의 참가자여야 함).
    // 참가 행은 만들자마자 release()한다 — 이 테스트가 이어서 apply()로 호스트에게 새 활성 참가 행을 만들기
    // 때문에, 과거 매칭의 참가 행이 활성으로 남아 있으면 uq_participant_active_user에 걸린다. 그리고 그 해제가
    // 다음 INSERT보다 먼저 DB에 반영되도록 saveAndFlush()를 쓴다(같은 플러시 안에서는 INSERT가 UPDATE보다 앞선다).
    // match_request_id는 V11부터 nullable이라 별도 게시글 없이 null로 둔다.
    private void giveReviewToHost(String comment) {
        Long reviewerId = createUser("reviewer");

        OffsetDateTime base = OffsetDateTime.now().minusHours(2);
        ActivityMatch pastMatch = new ActivityMatch(
                base.plusMinutes(20), base.plusMinutes(30), TalkLevel.SILENT,
                "뚝섬 한강공원", "뚝섬 한강공원 코스", 5000, 12000,
                "뚝섬유원지역 3번 출구", 360, 400,
                base.plusMinutes(10), base
        );
        pastMatch.confirm("123456");
        pastMatch.end();
        Long pastMatchId = activityMatchRepository.save(pastMatch).getId();

        MatchParticipant hostSide = matchParticipantRepository.save(
                new MatchParticipant(pastMatchId, null, hostUserId, "A", AcceptStatus.ACCEPTED));
        MatchParticipant reviewerSide = matchParticipantRepository.save(
                new MatchParticipant(pastMatchId, null, reviewerId, "B", AcceptStatus.ACCEPTED));
        hostSide.release();
        reviewerSide.release();
        matchParticipantRepository.saveAndFlush(hostSide);
        matchParticipantRepository.saveAndFlush(reviewerSide);

        jdbcTemplate.update("""
                INSERT INTO activity_review
                    (activity_match_id, reviewer_user_id, reviewee_user_id, rating, perceived_talk_level, comment)
                VALUES (?, ?, ?, 5, 'SILENT', ?)
                """, pastMatchId, reviewerId, hostUserId, comment);
    }

    // EXPIRED(호스트가 응답 기한을 넘겨 시스템이 자동 만료시킨 경우)도 신청자 입장에서는
    // 호스트가 직접 거절한 것과 결과가 같으므로("내 신청이 받아들여지지 않음") REJECTED
    // 버킷으로 묶인다 — CANCELLED(본인이 취소)와 구분되는 지점.
    @Test
    void 자동_만료된_신청은_REJECTED로_매핑된다() {
        Long expiredHostUserId = createUser("host-expired");
        Long expiredHostRequestId = createHostRequest(expiredHostUserId);
        Long expiredId = matchApplyService.apply(applicantUserId, expiredHostRequestId);

        jdbcTemplate.update(
                """
                UPDATE activity_match
                SET created_at = CURRENT_TIMESTAMP - INTERVAL '2 seconds',
                    decision_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                WHERE id = ?
                """,
                expiredId
        );
        entityManager.clear();

        matchDecisionService.expireOverdue();

        assertThat(matchApplyService.getMyApplications(applicantUserId, "REJECTED"))
                .extracting(MyApplicationResponse::activityMatchId).containsExactly(expiredId);
    }

    // 탈퇴 상태(chk_users_account_lifecycle: 개인정보 전부 NULL + deleted_at 기록)로 변경한다.
    // 회원 탈퇴 API(UserService.withdraw)는 호스트 게시글을 함께 정리하므로, 여기서는 그 정리를 거치지 않고
    // 게시글이 SEARCHING으로 남아 있는 경우를 만들기 위해 SQL로 직접 처리한다.
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

    // 게시판 목록 필터(searchBoard)는 "발견"만 막을 뿐이다. 탈퇴한 호스트는 수락·거절을 영원히
    // 할 수 없어 신청자만 응답 기한까지 묶이므로, 캐시된 id로 들어오는 직접 신청도 거부해야 한다.
    // 탈퇴 여부가 드러나지 않도록 일반 마감 글과 같은 메시지를 쓴다.
    @Test
    void 탈퇴한_호스트의_모집글에는_신청할_수_없다() {
        withdrawUser(hostUserId);

        assertThatThrownBy(() -> matchApplyService.apply(applicantUserId, hostRequestId))
                .isInstanceOf(MatchRequestNotSearchingException.class)
                .hasMessage("이미 마감되었거나 신청이 진행 중인 모집글이에요.");

        assertThat(matchRequestRepository.findById(hostRequestId).orElseThrow().getStatus())
                .isEqualTo(MatchRequestStatus.SEARCHING);
    }

    // apply() 가드는 방어선이다. 회원 탈퇴 API는 신청 대기 매칭도 탈퇴 시점에 정리하지만(UserWithdrawIntegrationTest),
    // 정리를 거치지 않은 탈퇴(SQL로 직접 재현)로 호스트가 신청 대기 중 탈퇴한 상태가 되면 expireOverdue()가
    // 응답 기한 경과 후 게시글을 SEARCHING으로 되돌린다. 그렇게 부활한 글에 대한 새 신청은 막아야 한다.
    @Test
    void 호스트가_신청_대기_중_탈퇴해_게시글이_SEARCHING으로_복귀해도_새_신청은_거부된다() {
        Long activityMatchId = matchApplyService.apply(applicantUserId, hostRequestId);
        entityManager.flush(); // apply()의 게시글 상태 전이(PENDING_CONFIRMATION)를 DB에 반영

        withdrawUser(hostUserId);
        jdbcTemplate.update(
                """
                UPDATE activity_match
                SET created_at = CURRENT_TIMESTAMP - INTERVAL '2 seconds',
                    decision_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                WHERE id = ?
                """,
                activityMatchId
        );
        entityManager.clear();

        matchDecisionService.expireOverdue();

        // 탈퇴한 호스트의 게시글이 다시 SEARCHING이 된다(이 경로가 실제로 존재함을 확인)
        assertThat(matchRequestRepository.findById(hostRequestId).orElseThrow().getStatus())
                .isEqualTo(MatchRequestStatus.SEARCHING);

        Long anotherApplicantUserId = createUser("applicant2");
        assertThatThrownBy(() -> matchApplyService.apply(anotherApplicantUserId, hostRequestId))
                .isInstanceOf(MatchRequestNotSearchingException.class);
    }
}
