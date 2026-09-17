package com.team4.hanbbyeom.matching.service;

import com.team4.hanbbyeom.matching.domain.*;
import com.team4.hanbbyeom.matching.repository.MatchParticipantRepository;
import com.team4.hanbbyeom.matching.repository.MatchRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class MatchApplyServiceTest {

    @Autowired private MatchApplyService matchApplyService;
    @Autowired private MatchRequestRepository matchRequestRepository;
    @Autowired private MatchParticipantRepository matchParticipantRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

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
}