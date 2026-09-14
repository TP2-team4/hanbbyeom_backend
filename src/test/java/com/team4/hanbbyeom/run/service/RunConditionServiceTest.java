package com.team4.hanbbyeom.run.service;

import com.team4.hanbbyeom.matching.domain.MatchRequest;
import com.team4.hanbbyeom.matching.domain.TalkLevel;
import com.team4.hanbbyeom.matching.exception.MatchRequestNotFoundException;
import com.team4.hanbbyeom.matching.repository.MatchRequestRepository;
import com.team4.hanbbyeom.run.dto.RunConditionCreateRequest;
import com.team4.hanbbyeom.run.repository.RunMatchConditionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
public class RunConditionServiceTest {

    @Autowired
    private RunConditionService runConditionService;
    @Autowired
    private RunMatchConditionRepository runMatchConditionRepository;
    @Autowired
    private MatchRequestRepository matchRequestRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long ownerUserId;
    private Long otherUserId;
    private Long matchRequestId;
    private Long courseId;

    @BeforeEach
    void setUp() {
        ownerUserId = createUser("owner");
        otherUserId = createUser("other");

        courseId = jdbcTemplate.queryForObject(
                "SELECT id FROM running_course WHERE name = ? LIMIT 1", Long.class, "뚝섬 한강공원");

        MatchRequest matchRequest = new MatchRequest(
                ownerUserId, OffsetDateTime.now().plusHours(5), TalkLevel.SILENT,
                OffsetDateTime.now().plusHours(4)
        );
        matchRequestId = matchRequestRepository.save(matchRequest).getId();
    }

    private Long createUser(String label) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, nickname, email_verified_at) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class,
                label + "-" + System.nanoTime() + "@example.com", "dummy-hash", label, OffsetDateTime.now()
        );
    }

    private RunConditionCreateRequest validRequest() {
        return new RunConditionCreateRequest(
                matchRequestId, courseId, "뚝섬유원지역 3번 출구", 5000, 8000, 360, 400
        );
    }

    @Test
    void 정상_등록하면_저장되고_아이디가_반환된다() {
        Long savedId = runConditionService.create(ownerUserId, validRequest());

        assertThat(savedId).isEqualTo(matchRequestId);
        assertThat(runMatchConditionRepository.findById(savedId)).isPresent();
    }

    @Test
    void 존재하지_않는_matchRequestId면_예외가_발생한다() {
        RunConditionCreateRequest request = new RunConditionCreateRequest(
                999_999L, courseId, "출구", 5000, 8000, 360, 400
        );

        assertThatThrownBy(() -> runConditionService.create(ownerUserId, request))
                .isInstanceOf(MatchRequestNotFoundException.class);
    }

    @Test
    void 본인_소유가_아닌_matchRequest면_예외가_발생한다() {
        assertThatThrownBy(() -> runConditionService.create(otherUserId, validRequest()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void 존재하지_않는_코스면_예외가_발생한다() {
        RunConditionCreateRequest request = new RunConditionCreateRequest(
                matchRequestId, 999_999L, "출구", 5000, 8000, 360, 400
        );

        assertThatThrownBy(() -> runConditionService.create(ownerUserId, request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 거리가_허용_범위를_벗어나면_예외가_발생한다() {
        RunConditionCreateRequest request = new RunConditionCreateRequest(
                matchRequestId, courseId, "출구", 500, 8000, 360, 400 // 500m < 최소 1000m
        );

        assertThatThrownBy(() -> runConditionService.create(ownerUserId, request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 거리_최소값이_최대값보다_크면_예외가_발생한다() {
        RunConditionCreateRequest request = new RunConditionCreateRequest(
                matchRequestId, courseId, "출구", 8000, 5000, 360, 400 // min > max
        );

        assertThatThrownBy(() -> runConditionService.create(ownerUserId, request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 페이스가_허용_범위를_벗어나면_예외가_발생한다() {
        RunConditionCreateRequest request = new RunConditionCreateRequest(
                matchRequestId, courseId, "출구", 5000, 8000, 200, 400 // 200초 < 최소 300초
        );

        assertThatThrownBy(() -> runConditionService.create(ownerUserId, request))
                .isInstanceOf(IllegalArgumentException.class);
    }
}