package com.team4.hanbbyeom.matching.service;

import com.team4.hanbbyeom.matching.domain.*;
import com.team4.hanbbyeom.matching.dto.MatchRequestCreateRequest;
import com.team4.hanbbyeom.matching.exception.InvalidMatchRequestException;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class MatchApplyServiceTest {

    @Autowired private MatchApplyService matchApplyService;
    @Autowired private MatchRequestCommandService matchRequestCommandService;
    @Autowired private MatchRequestRepository matchRequestRepository;
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

    // 회귀 테스트: 팀원 리뷰로 발견된 정책 모순 — 게시글 등록은 3시간 뒤 일정부터 허용됐지만,
    // 신청은 24시간(DECISION_WINDOW_HOURS)보다 더 남아야만 성공해서, 3~24시간 뒤 일정으로
    // 등록된 글은 등록은 되지만 아무도 신청할 수 없었다. MatchRequestCommandService의 최소
    // 리드타임(MIN_LEAD_HOURS)을 DECISION_WINDOW_HOURS + 1시간으로 올려서 이 모순을 없앴다.
    @Test
    void 예전에는_등록됐지만_아무도_신청할_수_없었던_리드타임은_이제_등록_자체가_거부된다() {
        // 예전 기준(MIN_LEAD_HOURS=3시간)으로는 등록 성공했지만, 신청 기준(24시간)에는
        // 못 미치던 "죽은 구간" 리드타임.
        MatchRequestCreateRequest deadZoneRequest = new MatchRequestCreateRequest(
                courseId, "뚝섬유원지역 3번 출구", 5000, 8000, 360, 400,
                OffsetDateTime.now().plusHours(10), "SILENT"
        );

        assertThatThrownBy(() -> matchRequestCommandService.create(hostUserId, deadZoneRequest))
                .isInstanceOf(InvalidMatchRequestException.class);
    }

    @Test
    void 최소_리드타임으로_등록한_게시글은_등록_직후_바로_신청할_수_있다() {
        // MIN_LEAD_HOURS(=DECISION_WINDOW_HOURS+1시간) 경계에 최대한 가깝게 등록해도,
        // 등록 직후 신청이 실패하지 않아야 한다 — "등록은 되는데 신청은 못 하는" 모순이
        // 없어졌음을 등록→신청을 실제로 이어서 실행해 확인한다.
        MatchRequestCreateRequest boundaryRequest = new MatchRequestCreateRequest(
                courseId, "뚝섬유원지역 3번 출구", 5000, 8000, 360, 400,
                OffsetDateTime.now().plusHours(26), "SILENT"
        );
        Long newHostUserId = createUser("host2");
        Long newHostRequestId = matchRequestCommandService.create(newHostUserId, boundaryRequest);
        // RunMatchCondition은 @MapsId라 save() 시점에 즉시 INSERT되지 않고 flush까지 지연될 수
        // 있는데, apply()가 이걸 raw SQL로 직접 조회하므로 명시적으로 flush해서 보이게 한다
        // (MatchingFlowIntegrationTest와 동일한 이유 — 실제 운영에서는 create()와 apply()가
        // 항상 별개 트랜잭션이라 이 문제가 없음, 테스트에서만 필요).
        entityManager.flush();

        Long activityMatchId = matchApplyService.apply(applicantUserId, newHostRequestId);

        assertThat(activityMatchId).isNotNull();
    }
}