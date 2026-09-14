package com.team4.hanbbyeom.matching.service;

import com.team4.hanbbyeom.matching.domain.*;
import com.team4.hanbbyeom.matching.exception.NoActiveMatchRequestException;
import com.team4.hanbbyeom.matching.repository.MatchRequestRepository;
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
    @Autowired private MatchRequestRepository matchRequestRepository;
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
    void 신청자가_활성_모집글이_없으면_신청이_거부된다() {
        assertThatThrownBy(() -> matchApplyService.apply(applicantUserId, hostRequestId))
                .isInstanceOf(NoActiveMatchRequestException.class);
    }

    @Test
    void 신청_성공시_호스트와_신청자_모집글이_모두_PENDING_CONFIRMATION으로_바뀐다() {
        MatchRequest applicantRequest = new MatchRequest(
                applicantUserId, OffsetDateTime.now().plusHours(10), TalkLevel.LIGHT_CHAT,
                OffsetDateTime.now().plusHours(9)
        );
        Long applicantRequestId = matchRequestRepository.save(applicantRequest).getId();

        matchApplyService.apply(applicantUserId, hostRequestId);

        MatchRequest updatedHost = matchRequestRepository.findById(hostRequestId).orElseThrow();
        MatchRequest updatedApplicant = matchRequestRepository.findById(applicantRequestId).orElseThrow();

        assertThat(updatedHost.getStatus()).isEqualTo(MatchRequestStatus.PENDING_CONFIRMATION);
        assertThat(updatedApplicant.getStatus()).isEqualTo(MatchRequestStatus.PENDING_CONFIRMATION);
    }
}